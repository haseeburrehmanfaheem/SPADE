/*
--------------------------------------------------------------------------------
SPADE - Support for Provenance Auditing in Distributed Environments.
Copyright (C) 2011 SRI International

This program is free software: you can redistribute it and/or
modify it under the terms of the GNU General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
--------------------------------------------------------------------------------
 */
package spade.core;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedList;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import spade.opm.vertex.Network;

public class Kernel {

    public static final String SOURCE_REPORTER = "source_reporter";
    public static Map<String, AbstractSketch> remoteSketches;
    public static final int REMOTE_QUERY_PORT = 9999;
    public static final int REMOTE_SKETCH_PORT = 9998;
    private static final String configFile = "../cfg/spade.config";
    private static final String queryPipeInputPath = "../dev/queryPipeIn";
    private static final String controlPipeInputPath = "../dev/controlPipeIn";
    private static final String controlPipeOutputPath = "../dev/controlPipeOut";
    private static final String logFilenamePattern = "MM.dd.yyyy-H.mm.ss";
    private static final String NO_ARGUMENTS = "no arguments";
    private static final int BATCH_BUFFER_ELEMENTS = 100;
    private static final int MAIN_THREAD_SLEEP_DELAY = 3;
    private static final int COMMAND_THREAD_SLEEP_DELAY = 200;
    private static final int REMOVE_WAIT_DELAY = 300;
    private static final int FIRST_TRANSFORMER = 0;
    private static final int FIRST_FILTER = 0;
    private static Set<AbstractReporter> reporters;
    private static Set<AbstractStorage> storages;
    private static Set<AbstractReporter> removereporters;
    private static Set<AbstractStorage> removestorages;
    private static List<AbstractFilter> filters;
    private static List<AbstractFilter> transformers;
    private static Map<AbstractReporter, Buffer> buffers;
    public static Set<AbstractSketch> sketches;
    private static volatile boolean shutdown;
    private static volatile boolean flushTransactions;

    public static void main(String args[]) {

        try {
            // Configuring the global logger
            String logFilename = new java.text.SimpleDateFormat(logFilenamePattern).format(new java.util.Date(System.currentTimeMillis()));
            Handler logFileHandler = new FileHandler("../log/" + logFilename + ".log");
            Logger.getLogger("").addHandler(logFileHandler);
        } catch (Exception exception) {
            System.out.println("Error initializing exception logger");
        }

        // Basic initialization
        reporters = Collections.synchronizedSet(new HashSet<AbstractReporter>());
        storages = Collections.synchronizedSet(new HashSet<AbstractStorage>());
        removereporters = Collections.synchronizedSet(new HashSet<AbstractReporter>());
        removestorages = Collections.synchronizedSet(new HashSet<AbstractStorage>());
        transformers = Collections.synchronizedList(new LinkedList<AbstractFilter>());
        filters = Collections.synchronizedList(new LinkedList<AbstractFilter>());
        buffers = Collections.synchronizedMap(new HashMap<AbstractReporter, Buffer>());
        sketches = Collections.synchronizedSet(new HashSet<AbstractSketch>());
        remoteSketches = Collections.synchronizedMap(new HashMap<String, AbstractSketch>());
        shutdown = false;
        flushTransactions = false;

        // Initialize the SketchManager and the final commit filter.
        // The FinalCommitFilter acts as a terminator for the filter list
        // and also maintains a pointer to the list of active storages to which
        // the provenance data is finally passed. It also has a reference to
        // the SketchManager and triggers its putVertex() and putEdge() methods
        FinalCommitFilter commitFilter = new FinalCommitFilter();
        commitFilter.storages = storages;
        commitFilter.sketches = sketches;
        filters.add(commitFilter);

        // The final transformer is used to send vertex and edge objects to
        // their corresponding result Graph.
        FinalTransformer finalTransformer = new FinalTransformer();
        transformers.add(finalTransformer);


        // Initialize the main thread. This thread performs critical provenance-related
        // work inside SPADE. It extracts provenance objects (vertices, edges) from the
        // buffers, adds the source_reporter annotation to each object which is class name
        // of the reporter, and then sends these objects to the filter list.
        // This thread is also used for cleanly removing reporters and storages (through
        // the control commands and also when shutting down). This is done by ensuring that
        // once a reporter is marked for removal, the provenance objects from its buffer are
        // completely flushed.
        Runnable mainThread = new Runnable() {

            public void run() {
                try {
                    while (true) {
                        if (shutdown) {
                            // The shutdown process is also partially handled by this thread. On
                            // shutdown, all reporters are marked for removal so that their buffers
                            // are cleanly flushed and no data is lost. When a buffer becomes empty,
                            // it is removed along with its corresponding reporter.
                            for (Iterator iterator = buffers.entrySet().iterator(); iterator.hasNext();) {
                                Map.Entry currentEntry = (Map.Entry) iterator.next();
                                Buffer tempBuffer = (Buffer) currentEntry.getValue();
                                if (tempBuffer.isEmpty()) {
                                    iterator.remove();
                                }
                            }
                            if (buffers.isEmpty()) {
                                shutdown();
                                break;
                            }
                        }
                        if (flushTransactions) {
                            // Flushing of transactions is also handled by this thread to ensure that
                            // there are no errors/problems when using storages that are sensitive to
                            // thread-context for their transactions. For example, this is true for
                            // the embedded neo4j graph database.
                            for (AbstractStorage currentStorage : storages) {
                                currentStorage.flushTransactions();
                            }
                            flushTransactions = false;
                        }
                        if (!removestorages.isEmpty()) {
                            for (Iterator iterator = removestorages.iterator(); iterator.hasNext();) {
                                AbstractStorage currentStorage = (AbstractStorage) iterator.next();
                                currentStorage.shutdown();
                                iterator.remove();
                            }
                        }
                        for (Iterator iterator = buffers.entrySet().iterator(); iterator.hasNext();) {
                            Map.Entry currentEntry = (Map.Entry) iterator.next();
                            AbstractReporter reporter = (AbstractReporter) currentEntry.getKey();
                            for (int i = 0; i < BATCH_BUFFER_ELEMENTS; i++) {
                                Object bufferelement = ((Buffer) currentEntry.getValue()).getBufferElement();
                                if (bufferelement instanceof AbstractVertex) {
                                    AbstractVertex tempVertex = (AbstractVertex) bufferelement;
                                    tempVertex.addAnnotation(SOURCE_REPORTER, reporter.getClass().getName().split("\\.")[2]);
                                    ((AbstractFilter) filters.get(FIRST_FILTER)).putVertex(tempVertex);
                                } else if (bufferelement instanceof AbstractEdge) {
                                    AbstractEdge tempEdge = (AbstractEdge) bufferelement;
                                    tempEdge.addAnnotation(SOURCE_REPORTER, reporter.getClass().getName().split("\\.")[2]);
                                    ((AbstractFilter) filters.get(FIRST_FILTER)).putEdge((AbstractEdge) bufferelement);
                                } else if (bufferelement == null) {
                                    if (removereporters.contains(reporter)) {
                                        removereporters.remove(reporter);
                                        iterator.remove();
                                    }
                                    break;
                                }
                            }
                        }
                        Thread.sleep(MAIN_THREAD_SLEEP_DELAY);
                    }
                } catch (Exception exception) {
                    Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
                }
            }
        };
        new Thread(mainThread, "mainThread").start();


        // This thread creates the input and output pipes used for control (and also used
        // by the control client). The exit value is used to determine if the pipes were
        // successfully created. The input pipe (to which commands are issued) is read in
        // a loop and the commands are processed.
        Runnable controlThread = new Runnable() {

            public void run() {
                try {
                    int exitValue1 = Runtime.getRuntime().exec("mkfifo " + controlPipeInputPath).waitFor();
                    int exitValue2 = Runtime.getRuntime().exec("mkfifo " + controlPipeOutputPath).waitFor();
                    if (exitValue1 == 0 && exitValue2 == 0) {
                        configCommand("config load " + configFile, NullStream.out);
                        BufferedReader controlInputStream = new BufferedReader(new FileReader(controlPipeInputPath));
                        PrintStream controlOutputStream = new PrintStream(new FileOutputStream(controlPipeOutputPath));
                        System.setIn(new FileInputStream(controlPipeInputPath));
                        System.setOut(controlOutputStream);
                        System.setErr(controlOutputStream);
                        while (true) {
                            if (controlInputStream.ready()) {
                                String line = controlInputStream.readLine();
                                if ((line != null) && (executeCommand(line, controlOutputStream) == false)) {
                                    break;
                                }
                                controlOutputStream.println("");
                            }
                            Thread.sleep(COMMAND_THREAD_SLEEP_DELAY);
                        }
                    }
                } catch (Exception exception) {
                    Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
                }
            }
        };
        new Thread(controlThread, "controlThread").start();


        // Construct the query pipe. The exit value is used to determine if the
        // query pipe was successfully created.
        Runnable queryThread = new Runnable() {

            public void run() {
                try {
                    int exitValue1 = Runtime.getRuntime().exec("mkfifo " + queryPipeInputPath).waitFor();
                    if (exitValue1 == 0) {
                        BufferedReader queryInputStream = new BufferedReader(new FileReader(queryPipeInputPath));
                        while (!shutdown) {
                            if (queryInputStream.ready()) {
                                String line = queryInputStream.readLine();
                                if (line != null) {
                                    try {
                                        String[] queryTokens = line.split("\\s+", 2);
                                        // Only accept query commands from this pipe
                                        // The second argument in the query command is used to specify the
                                        // output for this query (i.e., a file or a pipe). This argument is
                                        // stripped from the query string and is passed as a separate argument
                                        // to the queryCommand() as the output stream.
                                        PrintStream queryOutputStream = new PrintStream(new FileOutputStream(queryTokens[0], false));
                                        if (queryTokens.length == 1) {
                                            showQueryCommands(queryOutputStream);
                                        } else if (queryTokens[1].startsWith("query ")) {
                                            queryCommand(queryTokens[1], queryOutputStream);
                                        } else {
                                            showQueryCommands(queryOutputStream);
                                        }
                                        queryOutputStream.println("");
                                        queryOutputStream.close();
                                    } catch (Exception exception) {
                                    }
                                }
                            }
                            Thread.sleep(COMMAND_THREAD_SLEEP_DELAY);
                        }
                    }
                } catch (Exception exception) {
                    Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
                }
            }
        };
        new Thread(queryThread, "queryThread").start();

        Runnable remoteThread = new Runnable() {

            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(REMOTE_QUERY_PORT);
                    while (!shutdown) {
                        QueryConnection thisConnection = new QueryConnection(serverSocket.accept());
                        thisConnection.start();
                    }
                } catch (Exception exception) {
                    Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
                }
            }
        };
        new Thread(remoteThread, "remoteThread").start();

        Runnable sketchThread = new Runnable() {

            public void run() {
                try {
                    ServerSocket serverSocket = new ServerSocket(REMOTE_SKETCH_PORT);
                    while (!shutdown) {
                        SketchConnection thisConnection = new SketchConnection(serverSocket.accept());
                        thisConnection.start();
                    }
                } catch (Exception exception) {
                    Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
                }
            }
        };
        new Thread(sketchThread, "sketchThread").start();

    }

    // The following two methods are called by the Graph object when adding vertices
    // and edges to the result graph. Transformers are technically the same as filters
    // and are used to modify/transform data as it is entered into a Graph object.    
    public static void sendToTransformers(AbstractVertex vertex) {
        ((AbstractFilter) transformers.get(FIRST_TRANSFORMER)).putVertex(vertex);
    }

    public static void sendToTransformers(AbstractEdge edge) {
        ((AbstractFilter) transformers.get(FIRST_TRANSFORMER)).putEdge(edge);
    }

    // All command strings are passed to this function which subsequently calls the
    // correct method based on the command. Each command is determined by the first
    // token in the string.
    public static boolean executeCommand(String line, PrintStream outputStream) {
        String command = line.split("\\s+")[0];
        if (command.equalsIgnoreCase("shutdown")) {
            // On shutdown, save the current configuration in the default configuration
            // file.
            configCommand("config save " + configFile, outputStream);
            for (AbstractReporter reporter : reporters) {
                // Shut down all reporters. After
                // this, their buffers are flushed and then the storages are shut down.
                reporter.shutdown();
            }
            shutdown = true;
            return false;
        } else if (command.equalsIgnoreCase("add")) {
            addCommand(line, outputStream);
            return true;
        } else if (command.equalsIgnoreCase("list")) {
            listCommand(line, outputStream);
            return true;
        } else if (command.equalsIgnoreCase("remove")) {
            removeCommand(line, outputStream);
            return true;
        } else if (command.equalsIgnoreCase("query")) {
            queryCommand(line, outputStream);
            return true;
        } else if (command.equalsIgnoreCase("config")) {
            configCommand(line, outputStream);
            return true;
        } else {
            showCommands(outputStream);
            return true;
        }
    }

    // The configCommand is used to load or save the current SPADE configuration
    // from/to a file.
    public static void configCommand(String line, PrintStream outputStream) {
        String[] tokens = line.split("\\s+");
        try {
            if (tokens[1].equalsIgnoreCase("load")) {
                BufferedReader configReader = new BufferedReader(new FileReader(tokens[2]));
                String configLine;
                while ((configLine = configReader.readLine()) != null) {
                    addCommand("add " + configLine, outputStream);
                }
                outputStream.println("Finished loading configuration file");
            } else if (tokens[1].equalsIgnoreCase("save")) {
                outputStream.print("Saving configuration... ");
                FileWriter configWriter = new FileWriter(tokens[2], false);
                for (int i = 0; i < filters.size() - 1; i++) {
                    configWriter.write("filter " + filters.get(i).getClass().getName().split("\\.")[2] + " " + i + "\n");
                }
                for (AbstractStorage storage : storages) {
                    String arguments = storage.arguments;
                    configWriter.write("storage " + storage.getClass().getName().split("\\.")[2] + " " + arguments + "\n");
                }
                for (AbstractReporter reporter : reporters) {
                    String arguments = reporter.arguments;
                    configWriter.write("reporter " + reporter.getClass().getName().split("\\.")[2] + " " + arguments + "\n");
                }
                configWriter.close();
                outputStream.println("done");
            } else {
                throw new Exception();
            }
        } catch (Exception exception) {
            outputStream.println("Usage: config load|save <filename>");
        }
    }

    public static Graph getPathFragment(AbstractSketch inputSketch) {
        Graph result = new Graph();

        Graph myNetworkVertices = query("query Neo4j vertices type:Network", false);
        Set<AbstractVertex> matchingVerticesDown = new HashSet<AbstractVertex>();
        Set<AbstractVertex> matchingVerticesUp = new HashSet<AbstractVertex>();
        MatrixFilter receivedMatrixFilter = inputSketch.matrixFilter;
        MatrixFilter myMatrixFilter = inputSketch.matrixFilter;

        // Current host's network vertices that match downward
        for (AbstractVertex currentDestionationVertex : (Set<AbstractVertex>) inputSketch.objects.get("dstVertices")) {
            BloomFilter currentBloomFilter = receivedMatrixFilter.get(currentDestionationVertex);
            for (AbstractVertex vertexToCheck : myNetworkVertices.vertexSet()) {
                AbstractVertex tempVertex = new Network();
                tempVertex.getAnnotations().putAll(vertexToCheck.getAnnotations());
                tempVertex.removeAnnotation("storageId");
                if (currentBloomFilter.contains(tempVertex.toString())) {
                    matchingVerticesDown.add(vertexToCheck);
                }
            }
        }

        // Current host's network vertices that match upward
        for (AbstractVertex vertexToCheck : myNetworkVertices.vertexSet()) {
            AbstractVertex tempVertex = new Network();
            tempVertex.getAnnotations().putAll(vertexToCheck.getAnnotations());
            tempVertex.removeAnnotation("storageId");
            BloomFilter currentBloomFilter = myMatrixFilter.get(tempVertex);
            for (AbstractVertex currentSourceVertex : (Set<AbstractVertex>) inputSketch.objects.get("srcVertices")) {
                if (currentBloomFilter.contains(currentSourceVertex.toString())) {
                    matchingVerticesUp.add(vertexToCheck);
                }
            }
        }

        // Network vertices that we're interested in
        Set<AbstractVertex> matchingVertices = new HashSet<AbstractVertex>();
        matchingVertices.addAll(matchingVerticesDown);
        matchingVertices.retainAll(matchingVerticesUp);

        // Get all paths between the matching network vertices
        Object vertices[] = matchingVertices.toArray();
        for (int i = 0; i < vertices.length; i++) {
            for (int j = 0; j < vertices.length; j++) {
                if (j == i) {
                    continue;
                }
                String srcId = ((AbstractVertex) vertices[i]).getAnnotation("storageId");
                String dstId = ((AbstractVertex) vertices[j]).getAnnotation("storageId");
                Graph path = query("query Neo4j paths " + srcId + " " + dstId + " 100", false);
                if (!path.edgeSet().isEmpty()) {
                    result = Graph.union(result, path);
                }
            }
        }

        return result;
    }

    public static void propagateSketches(int currentLevel, int maxLevel) {
        if (currentLevel == maxLevel) {
            return;
        }
        currentLevel++;
        Set<AbstractVertex> upVertices = storages.iterator().next().getEdges("type:Network", null, "type:WasGeneratedBy").vertexSet();
        for (AbstractVertex currentVertex : upVertices) {
            try {
                String remoteHost = currentVertex.getAnnotation("remote host");
                Socket remoteSocket = new Socket(remoteHost, REMOTE_SKETCH_PORT);
                PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                remoteSocketOut.println("propagateSketches " + currentLevel + " " + maxLevel);
                ObjectOutputStream remoteSocketObjectOutputStream = new ObjectOutputStream(remoteSocket.getOutputStream());
                remoteSocketObjectOutputStream.writeObject(remoteSketches);
                remoteSocketOut.close();
                remoteSocket.close();
            } catch (Exception exception) {
                Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
            }
        }
    }

    public static void notifyRebuildSketches(int currentLevel, int maxLevel) {
        if (currentLevel == 0) {
            propagateSketches(0, maxLevel);
            return;
        }
        currentLevel--;
        Set<AbstractVertex> upVertices = storages.iterator().next().getEdges(null, "type:Network", "type:Used").vertexSet();
        for (AbstractVertex currentVertex : upVertices) {
            try {
                String remoteHost = currentVertex.getAnnotation("remote host");
                Socket remoteSocket = new Socket(remoteHost, REMOTE_SKETCH_PORT);
                PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                remoteSocketOut.println("notifyRebuildSketches " + currentLevel + " " + maxLevel);
                remoteSocketOut.close();
                remoteSocket.close();
            } catch (Exception exception) {
                Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
            }
        }
    }

    public static boolean checkPathInSketch(String line) {
        // line has format "sourceHost:vertexId destinationHost:vertexId"
        String source = line.split("\\s")[0];
        String srcHost = source.split(":")[0];
        String srcVertexId = source.split(":")[1];
        String destination = line.split("\\s")[1];
        String dstHost = destination.split(":")[0];
        String dstVertexId = destination.split(":")[1];

        Set<AbstractVertex> destinationNetworkVertices = new HashSet<AbstractVertex>();
        Set<AbstractVertex> sourceNetworkVertices = new HashSet<AbstractVertex>();

        try {
            // Consider path query A/*/B
            // Connect to host(B) and get all network vertices that connect to B
            Socket remoteSocket = new Socket(dstHost, REMOTE_QUERY_PORT);
            String expression = "query Neo4j vertices type:Network";
            PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
            BufferedReader remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
            ObjectInputStream graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
            remoteSocketOut.println(expression);
            // Check whether the remote query server returned a graph in response
            Graph tmpGraph = (Graph) graphInputStream.readObject();
            // Add those network vertices to the destination set that have a path
            // to the specified vertex
            for (AbstractVertex currentVertex : tmpGraph.vertexSet()) {
                expression = "query Neo4j paths " + currentVertex.getAnnotation("storageId") + " " + dstVertexId + " 100";
                remoteSocketOut.println(expression);
                Graph currentGraph = (Graph) graphInputStream.readObject();
                if (!currentGraph.edgeSet().isEmpty()) {
                    destinationNetworkVertices.add(currentVertex);
                }
            }

            // Similarly, populate the source network vertices from the source host
            graphInputStream.close();
            remoteSocketOut.close();
            remoteSocketIn.close();
            remoteSocket.close();

            remoteSocket = new Socket(srcHost, REMOTE_QUERY_PORT);
            expression = "query Neo4j vertices type:Network";
            remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
            remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
            graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
            remoteSocketOut.println(expression);
            // Check whether the remote query server returned a graph in response
            tmpGraph = (Graph) graphInputStream.readObject();
            for (AbstractVertex currentVertex : tmpGraph.vertexSet()) {
                expression = "query Neo4j paths " + srcVertexId + " " + currentVertex.getAnnotation("storageId") + " 100";
                remoteSocketOut.println(expression);
                Graph currentGraph = (Graph) graphInputStream.readObject();
                if (!currentGraph.edgeSet().isEmpty()) {
                    sourceNetworkVertices.add(currentVertex);
                }
            }
            graphInputStream.close();
            remoteSocketOut.close();
            remoteSocketIn.close();
            remoteSocket.close();

            BloomFilter resultBloomFilter = null;
            for (AbstractSketch currentSketch : remoteSketches.values()) {
                for (AbstractVertex currentBVertex : destinationNetworkVertices) {
                    if (currentSketch.matrixFilter.contains(currentBVertex.toString())) {
                        BloomFilter currentBloomFilter = currentSketch.matrixFilter.get(currentBVertex);
                        if (resultBloomFilter == null) {
                            resultBloomFilter = currentBloomFilter;
                        } else {
                            resultBloomFilter.getBitSet().or(currentBloomFilter.getBitSet());
                        }
                    }
                }
            }

            for (AbstractVertex currentAVertex : sourceNetworkVertices) {
                if (resultBloomFilter.contains(currentAVertex.toString())) {
                    return true;
                }
            }
        } catch (Exception exception) {
            Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
        }

        return false;
    }

    public static Graph getPathInSketch(String line) {
        Graph result = new Graph();

        // line has format "sourceHost:vertexId destinationHost:vertexId"
        String source = line.split("\\s")[0];
        String srcHost = source.split(":")[0];
        String srcVertexId = source.split(":")[1];
        String destination = line.split("\\s")[1];
        String dstHost = destination.split(":")[0];
        String dstVertexId = destination.split(":")[1];

        Set<AbstractVertex> destinationNetworkVertices = new HashSet<AbstractVertex>();
        Set<AbstractVertex> sourceNetworkVertices = new HashSet<AbstractVertex>();

        try {
            // Consider path query A/*/B
            // Connect to host(B) and get all network vertices that connect to B
            Socket remoteSocket = new Socket(dstHost, REMOTE_QUERY_PORT);
            String expression = "query Neo4j vertices type:Network";
            PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
            ObjectInputStream graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
            remoteSocketOut.println(expression);
            // Check whether the remote query server returned a graph in response
            Graph tmpGraph = (Graph) graphInputStream.readObject();
            // Add those network vertices to the destination set that have a path
            // to the specified vertex
            for (AbstractVertex currentVertex : tmpGraph.vertexSet()) {
                expression = "query Neo4j paths " + currentVertex.getAnnotation("storageId") + " " + dstVertexId + " 100";
                remoteSocketOut.println(expression);
                Graph currentGraph = (Graph) graphInputStream.readObject();
                if (!currentGraph.edgeSet().isEmpty()) {
                    destinationNetworkVertices.add(currentVertex);
                }
            }

            // Similarly, populate the source network vertices from the source host
            graphInputStream.close();
            remoteSocketOut.close();
            remoteSocket.close();

            remoteSocket = new Socket(srcHost, REMOTE_QUERY_PORT);
            expression = "query Neo4j vertices type:Network";
            remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
            graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
            remoteSocketOut.println(expression);
            // Check whether the remote query server returned a graph in response
            tmpGraph = (Graph) graphInputStream.readObject();
            for (AbstractVertex currentVertex : tmpGraph.vertexSet()) {
                expression = "query Neo4j paths " + srcVertexId + " " + currentVertex.getAnnotation("storageId") + " 100";
                remoteSocketOut.println(expression);
                Graph currentGraph = (Graph) graphInputStream.readObject();
                if (!currentGraph.edgeSet().isEmpty()) {
                    sourceNetworkVertices.add(currentVertex);
                }
            }
            graphInputStream.close();
            remoteSocketOut.close();
            remoteSocket.close();

            List<String> hostsToContact = new LinkedList<String>();

            for (Map.Entry<String, AbstractSketch> currentEntry : remoteSketches.entrySet()) {
                BloomFilter ancestorFilter = currentEntry.getValue().matrixFilter.getAllBloomFilters();
                for (AbstractVertex currentAVertex : sourceNetworkVertices) {
                    if (ancestorFilter.contains(currentAVertex.toString())) {
                        // Send B's sketch to this host to get the path fragment
                        hostsToContact.add(currentEntry.getKey());
                        break;
                    }
                }
            }

            AbstractSketch mySketch = sketches.iterator().next();
            mySketch.objects.put("srcVertices", sourceNetworkVertices);
            mySketch.objects.put("dstVertices", destinationNetworkVertices);

            for (int i = 0; i < hostsToContact.size(); i++) {
                // Connect to each host and send it B's sketch
                remoteSocket = new Socket(hostsToContact.get(i), REMOTE_SKETCH_PORT);
                remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                ObjectOutputStream remoteSocketObjectOutputStream = new ObjectOutputStream(remoteSocket.getOutputStream());
                graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
                // Send the sketch
                remoteSocketOut.println("pathFragment");
                remoteSocketObjectOutputStream.writeObject(mySketch);
                // Receive the graph fragment
                tmpGraph = (Graph) graphInputStream.readObject();
                graphInputStream.close();
                remoteSocketOut.close();
                remoteSocket.close();
                // Add this fragment to the result
                result = Graph.union(result, tmpGraph);
            }

        } catch (Exception exception) {
            Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
        }

        return result;
    }

    // This method is used to call query methods on the desired storage. The
    // transactions are also flushed to ensure that the data in the storages is
    // consistent and updated with all the data received by SPADE up to this point.
    public static Graph query(String line, boolean resolveRemote) {
        Graph resultGraph = null;
        flushTransactions = true;
        while (flushTransactions) {
            // wait for other thread to flush transactions
        }
        if (storages.isEmpty()) {
            return null;
        }
        String[] tokens = line.split("\\s+");
        for (AbstractStorage storage : storages) {
            if (storage.getClass().getName().equals("spade.storage." + tokens[1])) {
                // Determine the type of query and call the corresponding method
                if (tokens[2].equalsIgnoreCase("vertices")) {
                    String queryExpression = "";
                    for (int i = 3; i < tokens.length; i++) {
                        queryExpression = queryExpression + tokens[i] + " ";
                    }
                    try {
                        resultGraph = storage.getVertices(queryExpression.trim());
                    } catch (Exception badQuery) {
                        return null;
                    }
                } else if (tokens[2].equalsIgnoreCase("remotevertices")) {
                    String host = tokens[3];
                    String queryExpression = "";
                    for (int i = 4; i < tokens.length; i++) {
                        queryExpression = queryExpression + tokens[i] + " ";
                    }
                    if (host.equalsIgnoreCase("localhost")) {
                        try {
                            resultGraph = storage.getVertices(queryExpression.trim());
                        } catch (Exception badQuery) {
                            return null;
                        }
                    } else {
                        try {
                            Socket remoteSocket = new Socket(host, REMOTE_QUERY_PORT);
                            String srcExpression = "query vertices " + queryExpression;
                            PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                            BufferedReader remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
                            ObjectInputStream graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
                            remoteSocketOut.println(srcExpression);
                            resultGraph = (Graph) graphInputStream.readObject();
                            graphInputStream.close();
                            remoteSocketOut.close();
                            remoteSocketIn.close();
                            remoteSocket.close();
                        } catch (Exception badQuery) {
                            return null;
                        }
                    }
                } else if (tokens[2].equalsIgnoreCase("lineage")) {
                    String vertexId = tokens[3];
                    int depth = Integer.parseInt(tokens[4]);
                    String direction = tokens[5];
                    String terminatingExpression = "";
                    for (int i = 6; i < tokens.length - 1; i++) {
                        terminatingExpression = terminatingExpression + tokens[i] + " ";
                    }
                    try {
                        resultGraph = storage.getLineage(vertexId, depth, direction, terminatingExpression.trim());
                    } catch (Exception badQuery) {
                        return null;
                    }
                    if (resolveRemote) {
                        // Perform the remote queries here. A temporary remoteGraph is
                        // created to store the results of the remote queries and then
                        // added to the final resultGraph
                        Graph remoteGraph = new Graph();
                        // Get the map of network vertexes of our current graph
                        Map<AbstractVertex, Integer> currentNetworkMap = resultGraph.networkMap();
                        // Perform remote queries until the network map is exhausted
                        while (!currentNetworkMap.isEmpty()) {
                            // Perform remote query on current network vertex and union
                            // the result with the remoteGraph. This also adds the network
                            // vertexes to the remoteGraph as well, so that deeper level
                            // network queries are resolved iteratively
                            for (Map.Entry currentEntry : currentNetworkMap.entrySet()) {
                                AbstractVertex networkVertex = (AbstractVertex) currentEntry.getKey();
                                int currentDepth = (Integer) currentEntry.getValue();
                                // Execute remote query
                                Graph tempRemoteGraph = queryNetworkVertex(networkVertex, depth - currentDepth, direction, terminatingExpression.trim());
                                // Update the depth values of all network artifacts in the
                                // remote network map to reflect current level of iteration
                                for (Map.Entry currentNetworkEntry : tempRemoteGraph.networkMap().entrySet()) {
                                    AbstractVertex tempNetworkVertex = (AbstractVertex) currentNetworkEntry.getKey();
                                    int updatedDepth = currentDepth + (Integer) currentNetworkEntry.getValue();
                                    tempRemoteGraph.putNetworkVertex(tempNetworkVertex, updatedDepth);
                                }
                                // Add the lineage of the current network node to the
                                // overall result
                                remoteGraph = Graph.union(remoteGraph, tempRemoteGraph);
                            }
                            currentNetworkMap.clear();
                            // Set the networkMap to network vertexes of the newly
                            // create remoteGraph
                            currentNetworkMap = remoteGraph.networkMap();
                        }
                        resultGraph = Graph.union(resultGraph, remoteGraph);
                    }
                } else if (tokens[2].equalsIgnoreCase("paths")) {
                    String srcVertexId = tokens[3];
                    String dstVertexId = tokens[4];
                    int maxLength = Integer.parseInt(tokens[5]);
                    try {
                        resultGraph = storage.getPaths(srcVertexId, dstVertexId, maxLength);
                    } catch (Exception badQuery) {
                        return null;
                    }
                } else if (tokens[2].equalsIgnoreCase("pathinsketch")) {
                    resultGraph = getPathInSketch(tokens[3] + " " + tokens[4]);
                } else if (tokens[2].equalsIgnoreCase("rebuildsketches")) {
                    notifyRebuildSketches(Integer.parseInt(tokens[3]), Integer.parseInt(tokens[4]));
                    return null;
                } else if (tokens[2].equalsIgnoreCase("remotepaths")) {
                    String source = tokens[3];
                    String srcHost = source.split(":")[0];
                    String srcVertexId = source.split(":")[1];
                    String destination = tokens[4];
                    String dstHost = destination.split(":")[0];
                    String dstVertexId = destination.split(":")[1];
                    int maxLength = Integer.parseInt(tokens[5]);
                    try {
                        if (srcHost.equalsIgnoreCase("localhost") && dstHost.equalsIgnoreCase("localhost")) {
                            resultGraph = storage.getPaths(srcVertexId, dstVertexId, maxLength);
                        } else {
                            Graph srcGraph = null, dstGraph = null;

                            Socket remoteSocket = new Socket(srcHost, REMOTE_QUERY_PORT);
                            String srcExpression = "query Neo4j lineage " + srcVertexId + " " + maxLength + " a type:*";
                            PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                            BufferedReader remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
                            ObjectInputStream graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
                            remoteSocketOut.println(srcExpression);

                            // Check whether the remote query server returned a graph in response
                            srcGraph = (Graph) graphInputStream.readObject();
                            graphInputStream.close();
                            remoteSocketOut.close();
                            remoteSocketIn.close();
                            remoteSocket.close();

                            remoteSocket = new Socket(dstHost, REMOTE_QUERY_PORT);
                            String dstExpression = "query Neo4j lineage " + dstVertexId + " " + maxLength + " d type:*";
                            remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
                            remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
                            graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());
                            remoteSocketOut.println(dstExpression);

                            // Check whether the remote query server returned a graph in response
                            dstGraph = (Graph) graphInputStream.readObject();
                            graphInputStream.close();
                            remoteSocketOut.close();
                            remoteSocketIn.close();
                            remoteSocket.close();

                            resultGraph = Graph.intersection(srcGraph, dstGraph);
                        }
                    } catch (Exception badQuery) {
                        return null;
                    }
                } else {
                    return null;
                }
            }
        }
        // If the graph is incomplete, perform the necessary remote queries
        return resultGraph;
    }

    public static Graph queryNetworkVertex(AbstractVertex networkVertex, int depth, String direction, String terminatingExpression) {
        Graph resultGraph = null;

        try {
            // Establish a connection to the remote host
            Socket remoteSocket = new Socket(networkVertex.getAnnotation("destination host"), REMOTE_QUERY_PORT);
            // The first query is used to determine the vertex id of the network
            // vertex on the remote host. This is needed to execute the lineage
            // query
            String vertexQueryExpression = "query Neo4j vertices ";
            for (Map.Entry currentEntry : networkVertex.getAnnotations().entrySet()) {
                String key = (String) currentEntry.getKey();
                String value = (String) currentEntry.getValue();
                vertexQueryExpression = vertexQueryExpression + key + ":\"" + value + "\" AND ";
            }
            vertexQueryExpression = vertexQueryExpression.substring(0, vertexQueryExpression.length() - 4);
            PrintWriter remoteSocketOut = new PrintWriter(remoteSocket.getOutputStream(), true);
            BufferedReader remoteSocketIn = new BufferedReader(new InputStreamReader(remoteSocket.getInputStream()));
            ObjectInputStream graphInputStream = new ObjectInputStream(remoteSocket.getInputStream());

            // Execute remote query for vertices
            remoteSocketOut.println(vertexQueryExpression);

            // Check whether the remote query server returned a graph in response
            Graph vertexGraph = (Graph) graphInputStream.readObject();
            // The graph should only have one vertex which is the network vertex.
            // We use this to get the vertex id
            int vertexId = Integer.parseInt(vertexGraph.vertexSet().iterator().next().getAnnotation("storageId"));
            // Build the expression for the remote lineage query
            String lineageQueryExpression = "query Neo4j lineage " + vertexId + " " + depth + " " + direction + " " + terminatingExpression;
            remoteSocketOut.println(lineageQueryExpression);

            // The graph object we get as a response is returned as the
            // result of this method
            resultGraph = (Graph) graphInputStream.readObject();

            graphInputStream.close();
            remoteSocketOut.close();
            remoteSocketIn.close();
            remoteSocket.close();
        } catch (Exception exception) {
            Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
        }

        return resultGraph;
    }

    public static String convertVertexToString(AbstractVertex vertex) {
        String vertexString = "";
        for (Map.Entry currentEntry : vertex.getAnnotations().entrySet()) {
            String key = (String) currentEntry.getKey();
            String value = (String) currentEntry.getValue();
            vertexString = vertexString + key + ":" + value + "|";
        }
        vertexString = vertexString.substring(0, vertexString.length() - 1);
        return vertexString;
    }

    // Call the main query method.
    public static void queryCommand(String line, PrintStream outputStream) {
        Graph resultGraph = query(line, false);
        if (resultGraph != null) {
            String[] tokens = line.split("\\s+");
            String outputFile = tokens[tokens.length - 1];
            if (tokens[2].equalsIgnoreCase("vertices")) {
                for (AbstractVertex tempVertex : resultGraph.vertexSet()) {
                    outputStream.println("[" + convertVertexToString(tempVertex) + "]");
                }
            } else if (tokens[2].equalsIgnoreCase("lineage")) {
                resultGraph.exportDOT(outputFile);
                outputStream.println("Exported graph to " + outputFile);
            } else if (tokens[2].equalsIgnoreCase("paths")) {
                resultGraph.exportDOT(outputFile);
                outputStream.println("Exported graph to " + outputFile);
            }
        } else {
            outputStream.println("Error: Please check query expression");
        }
    }

    // Method to display control commands to the output stream. The control and query
    // commands are displayed using separate methods since these commands are issued
    // from different shells.
    public static void showCommands(PrintStream outputStream) {
        outputStream.println("Available commands:");
        outputStream.println("       add reporter|storage <class name> <initialization arguments>");
        outputStream.println("       add filter <class name> <index>");
        outputStream.println("       add sketch <class name> <storage class name>");
        outputStream.println("       remove reporter|storage|sketch <class name>");
        outputStream.println("       remove filter <index>");
        outputStream.println("       list reporters|storages|filters|sketches|all");
        outputStream.println("       config load|save <filename>");
        outputStream.println("       exit");
        outputStream.println("       shutdown");
    }

    // Method to display query commands to the given output stream.
    public static void showQueryCommands(PrintStream outputStream) {
        outputStream.println("Available commands:");
        outputStream.println("       query <class name> vertices <expression>");
        outputStream.println("       query <class name> lineage <vertex id> <depth> <direction> <terminating expression> <output file>");
        outputStream.println("       query <class name> paths <source vertex id> <destination vertex id> <max length> <output file>");
        outputStream.println("       exit");
    }

    public static void addCommand(String line, PrintStream outputStream) {
        String[] tokens = line.split("\\s+", 4);
        try {
            if (tokens[1].equalsIgnoreCase("reporter")) {
                String classname = tokens[2];
                String arguments = (tokens.length == 3) ? null : tokens[3];
                try {
                    // Get the reporter by classname and create a new instance.
                    AbstractReporter reporter = (AbstractReporter) Class.forName("spade.reporter." + classname).newInstance();
                    outputStream.print("Adding reporter " + classname + "... ");
                    // Create a new buffer and allocate it to this reporter.
                    Buffer buffer = new Buffer();
                    reporter.setBuffer(buffer);
                    if (reporter.launch(arguments)) {
                        // The launch() method must return true to indicate a successful launch.
                        // On true, the reporter is added to the reporters set and the buffer
                        // is put into a HashMap keyed by the reporter (this is used by the main
                        // SPADE thread to extract buffer elements).
                        reporter.arguments = (arguments == null) ? NO_ARGUMENTS : arguments;
                        buffers.put(reporter, buffer);
                        reporters.add(reporter);
                        outputStream.println("done");
                    } else {
                        outputStream.println("failed");
                    }
                } catch (Exception addReporterException) {
                    outputStream.println("Error: Unable to add reporter " + classname + " - please check class name and arguments");
                    return;
                }
            } else if (tokens[1].equalsIgnoreCase("storage")) {
                String classname = tokens[2];
                String arguments = (tokens.length == 3) ? null : tokens[3];
                try {
                    // Get the storage by classname and create a new instance.
                    AbstractStorage storage = (AbstractStorage) Class.forName("spade.storage." + classname).newInstance();
                    outputStream.print("Adding storage " + classname + "... ");
                    if (storage.initialize(arguments)) {
                        // The initialize() method must return true to indicate successful startup.
                        // On true, the storage is added to the storages set.
                        storage.arguments = (arguments == null) ? NO_ARGUMENTS : arguments;
                        storage.vertexCount = 0;
                        storage.edgeCount = 0;
                        storages.add(storage);
                        outputStream.println("done");
                    } else {
                        outputStream.println("failed");
                    }
                } catch (Exception addStorageException) {
                    outputStream.println("Error: Unable to add storage " + classname + " - please check class name and arguments");
                    return;
                }
            } else if (tokens[1].equalsIgnoreCase("filter")) {
                String classname = tokens[2];
                String arguments = tokens[3];
                try {
                    // Get the filter by classname and create a new instance.
                    AbstractFilter filter = (AbstractFilter) Class.forName("spade.filter." + classname).newInstance();
                    // The argument is the index at which the filter is to be inserted.
                    int index = Integer.parseInt(arguments);
                    if (index >= filters.size()) {
                        throw new Exception();
                    }
                    // Set the next filter of this newly added filter.
                    filter.setNextFilter((AbstractFilter) filters.get(index));
                    if (index > 0) {
                        // If the newly added filter is not the first in the list, then
                        // then configure the previous filter in the list to point to this
                        // newly added filter as its next.
                        ((AbstractFilter) filters.get(index - 1)).setNextFilter(filter);
                    }
                    outputStream.print("Adding filter " + classname + "... ");
                    // Add filter to the list.
                    filters.add(index, filter);
                    outputStream.println("done");
                } catch (Exception addFilterException) {
                    outputStream.println("Error: Unable to add filter " + classname + " - please check class name and index");
                    return;
                }
            } else if (tokens[1].equalsIgnoreCase("transformer")) {
                String classname = tokens[2];
                String arguments = tokens[3];
                try {
                    // Get the transformer by classname and create a new instance.
                    AbstractFilter filter = (AbstractFilter) Class.forName("spade.filter." + classname).newInstance();
                    // The argument is the index at which the transformer is to be inserted.
                    int index = Integer.parseInt(arguments);
                    if (index >= transformers.size()) {
                        throw new Exception();
                    }
                    // Set the next transformer of this newly added transformer.
                    filter.setNextFilter((AbstractFilter) transformers.get(index));
                    if (index > 0) {
                        // If the newly added transformer is not the first in the list, then
                        // then configure the previous transformer in the list to point to this
                        // newly added transformer as its next.
                        ((AbstractFilter) transformers.get(index - 1)).setNextFilter(filter);
                    }
                    outputStream.print("Adding transformer " + classname + "... ");
                    // Add transformer to the list of transformers.
                    transformers.add(index, filter);
                    outputStream.println("done");
                } catch (Exception addFilterException) {
                    outputStream.println("Error: Unable to add transformer " + classname + " - please check class name and index");
                    return;
                }
            } else if (tokens[1].equalsIgnoreCase("sketch")) {
                String classname = tokens[2];
                String storagename = tokens[3];
                try {
                    // Get the sketch by classname and create a new instance.
                    AbstractSketch sketch = (AbstractSketch) Class.forName("spade.sketch." + classname).newInstance();
                    // The argument is the storage class to which this sketch must refernce.
                    boolean found = false;
                    for (AbstractStorage storage : storages) {
                        // Search for the given storage in the storages set.
                        if (storage.getClass().getName().equals("spade.storage." + storagename)) {
                            // sketch.storage = storage;
                            found = true;
                        }
                    }
                    if (!found) {
                        throw new Exception();
                    }
                    outputStream.print("Adding sketch " + classname + "... ");
                    sketches.add(sketch);
                    outputStream.println("done");
                } catch (Exception addSketchException) {
                    outputStream.println("Error: Unable to add sketch " + classname + " - please check class name and storage name");
                    return;
                }
            } else {
                throw new Exception();
            }
        } catch (Exception addCommandException) {
            outputStream.println("Usage: add reporter|storage <class name> <initialization arguments>");
            outputStream.println("       add filter|transformer <class name> <index>");
            outputStream.println("       add sketch <class name> <storage class name>");
        }
    }

    public static void listCommand(String line, PrintStream outputStream) {
        String[] tokens = line.split("\\s+");
        try {
            if (tokens[1].equalsIgnoreCase("reporters")) {
                if (reporters.isEmpty()) {
                    // Nothing to list if the set of reporters is empty.
                    outputStream.println("No reporters added");
                    return;
                }
                outputStream.println(reporters.size() + " reporter(s) added:");
                int count = 1;
                for (AbstractReporter reporter : reporters) {
                    // Print the names and arguments of all reporters.
                    String arguments = reporter.arguments;
                    outputStream.println("\t" + count + ". " + reporter.getClass().getName().split("\\.")[2] + " (" + arguments + ")");
                    count++;
                }
            } else if (tokens[1].equalsIgnoreCase("storages")) {
                if (storages.isEmpty()) {
                    // Nothing to list if the set of storages is empty.
                    outputStream.println("No storages added");
                    return;
                }
                outputStream.println(storages.size() + " storage(s) added:");
                int count = 1;
                for (AbstractStorage storage : storages) {
                    // Print the names and arguments of all storages.
                    String arguments = storage.arguments;
                    outputStream.println("\t" + count + ". " + storage.getClass().getName().split("\\.")[2] + " (" + arguments + ")");
                    count++;
                }
            } else if (tokens[1].equalsIgnoreCase("filters")) {
                if (filters.size() == 1) {
                    // The size of the filters list will always be at least 1 because
                    // of the FinalCommitFilter. The user is not made aware of the
                    // presence of this filter and it is only used for committing
                    // provenance data to the storages. Therefore, there is nothing
                    // to list if the size of the filters list is 1.
                    outputStream.println("No filters added");
                    return;
                }
                outputStream.println((filters.size() - 1) + " filter(s) added:");
                for (int i = 0; i < filters.size() - 1; i++) {
                    // Loop through the filters list, printing their names (except
                    // for the last FinalCommitFilter).
                    outputStream.println("\t" + (i + 1) + ". " + filters.get(i).getClass().getName().split("\\.")[2]);
                }
            } else if (tokens[1].equalsIgnoreCase("transformers")) {
                if (transformers.size() == 1) {
                    // The size of the transformers list will always be at least 1 because
                    // of the FinalTransformer. The user is not made aware of the
                    // presence of this filter and it is only used for committing
                    // provenance data to the result Graph. Therefore, there is nothing
                    // to list if the size of the filters list is 1.
                    outputStream.println("No transformers added");
                    return;
                }
                outputStream.println((transformers.size() - 1) + " transformer(s) added:");
                for (int i = 0; i < transformers.size() - 1; i++) {
                    // Loop through the transformers list, printing their names (except
                    // for the last FinalTransformer).
                    outputStream.println("\t" + (i + 1) + ". " + transformers.get(i).getClass().getName().split("\\.")[2]);
                }
            } else if (tokens[1].equalsIgnoreCase("sketches")) {
                if (sketches.isEmpty()) {
                    // Nothing to list if the set of sketches is empty.
                    outputStream.println("No sketches added");
                    return;
                }
                outputStream.println(sketches.size() + " sketch(es) added:");
                int count = 1;
                for (AbstractSketch sketch : sketches) {
                    // Print the names of all sketches.
                    outputStream.println("\t" + count + ". " + sketch.getClass().getName().split("\\.")[2]);
                    count++;
                }
            } else if (tokens[1].equalsIgnoreCase("all")) {
                listCommand("list reporters", outputStream);
                listCommand("list storages", outputStream);
                listCommand("list filters", outputStream);
                listCommand("list transformers", outputStream);
                listCommand("list sketches", outputStream);
            } else {
                throw new Exception();
            }
        } catch (Exception listCommandException) {
            outputStream.println("Usage: list reporters|storages|filters|transformers|sketches|all");
        }
    }

    public static void removeCommand(String line, PrintStream outputStream) {
        String[] tokens = line.split("\\s+");
        try {
            if (tokens[1].equalsIgnoreCase("reporter")) {
                boolean found = false;
                for (Iterator iterator = reporters.iterator(); iterator.hasNext();) {
                    AbstractReporter reporter = (AbstractReporter) iterator.next();
                    // Search for the given reporter in the set of reporters.
                    if (reporter.getClass().getName().equals("spade.reporter." + tokens[2])) {
                        // Mark the reporter for removal by adding it to the removereporters set.
                        // This will enable the main SPADE thread to cleanly flush the reporter
                        // buffer and remove it.
                        reporter.shutdown();
                        removereporters.add(reporter);
                        found = true;
                        outputStream.print("Shutting down reporter " + tokens[2] + "... ");
                        while (removereporters.contains(reporter)) {
                            // Wait for other thread to safely remove reporter
                            Thread.sleep(REMOVE_WAIT_DELAY);
                        }
                        iterator.remove();
                        outputStream.println("done");
                        break;
                    }
                }
                if (!found) {
                    outputStream.println("Reporter " + tokens[2] + " not found");
                }
            } else if (tokens[1].equalsIgnoreCase("storage")) {
                boolean found = false;
                for (Iterator iterator = storages.iterator(); iterator.hasNext();) {
                    AbstractStorage storage = (AbstractStorage) iterator.next();
                    // Search for the given storage in the storages set.
                    if (storage.getClass().getName().equals("spade.storage." + tokens[2])) {
                        // Mark the storage for removal by adding it to the removestorages set.
                        // This will enable the main SPADE thread to safely commit any transactions
                        // and then remove the storage.
                        long vertexCount = storage.vertexCount;
                        long edgeCount = storage.edgeCount;
                        removestorages.add(storage);
                        found = true;
                        outputStream.print("Shutting down storage " + tokens[2] + "... ");
                        while (removestorages.contains(storage)) {
                            // Wait for other thread to safely remove storage
                            Thread.sleep(REMOVE_WAIT_DELAY);
                        }
                        iterator.remove();
                        outputStream.println("done (" + vertexCount + " vertices and " + edgeCount + " edges added)");
                        break;
                    }
                }
                if (!found) {
                    outputStream.println("Storage " + tokens[2] + " not found");
                }
            } else if (tokens[1].equalsIgnoreCase("filter")) {
                // Filter removal is done by the index number (beginning from 1).
                int index = Integer.parseInt(tokens[2]);
                if ((index <= 0) || (index >= filters.size())) {
                    outputStream.println("Error: Unable to remove filter - bad index");
                    return;
                }
                String filterName = filters.get(index - 1).getClass().getName();
                outputStream.print("Removing filter " + filterName.split("\\.")[2] + "... ");
                if (index > 1) {
                    // Update the internal links between filters by calling the setNextFilter
                    // method on the filter just before the one being removed. The (index-1)
                    // check is used because this method is not to be called on the first filter.
                    ((AbstractFilter) filters.get(index - 2)).setNextFilter((AbstractFilter) filters.get(index));
                }
                filters.remove(index - 1);
                outputStream.println("done");
            } else if (tokens[1].equalsIgnoreCase("transformer")) {
                // Transformer removal is done by the index number (beginning from 1).
                int index = Integer.parseInt(tokens[2]);
                if ((index <= 0) || (index >= transformers.size())) {
                    outputStream.println("Error: Unable to remove transformer - bad index");
                    return;
                }
                String filterName = transformers.get(index - 1).getClass().getName();
                outputStream.print("Removing transformer " + filterName.split("\\.")[2] + "... ");
                if (index > 1) {
                    // Update the internal links between transformers by calling the setNextFilter
                    // method on the transformer just before the one being removed. The (index-1)
                    // check is used because this method is not to be called on the first transformer.
                    ((AbstractFilter) transformers.get(index - 2)).setNextFilter((AbstractFilter) transformers.get(index));
                }
                transformers.remove(index - 1);
                outputStream.println("done");
            } else if (tokens[1].equalsIgnoreCase("sketch")) {
                boolean found = false;
                for (Iterator iterator = sketches.iterator(); iterator.hasNext();) {
                    AbstractSketch sketch = (AbstractSketch) iterator.next();
                    // Search for the given sketch in the sketches set.
                    if (sketch.getClass().getName().equals("spade.sketch." + tokens[2])) {
                        found = true;
                        outputStream.print("Removing sketch " + tokens[2] + "... ");
                        iterator.remove();
                        outputStream.println("done");
                        break;
                    }
                }
                if (!found) {
                    outputStream.println("Sketch " + tokens[2] + " not found");
                }
            } else {
                throw new Exception();
            }
        } catch (Exception removeCommandException) {
            outputStream.println("Usage: remove reporter|storage|sketch <class name>");
            outputStream.println("       remove filter|transformer <index>");
        }
    }

    public static void shutdown() {
        for (AbstractStorage storage : storages) {
            // Shut down all storages.
            storage.shutdown();
        }
        try {
            // Remove the control and query pipes.
            Runtime.getRuntime().exec("rm -f " + controlPipeInputPath + " " + controlPipeOutputPath + " " + queryPipeInputPath).waitFor();
        } catch (Exception exception) {
            Logger.getLogger(Kernel.class.getName()).log(Level.SEVERE, null, exception);
        }
        System.exit(0);
    }
}

class FinalCommitFilter extends AbstractFilter {

    // Reference to the set of storages maintained by the Kernel.
    public Set<AbstractStorage> storages;
    public Set<AbstractSketch> sketches;

    // This filter is the last filter in the list so any vertices or edges
    // received by it need to be passed to the storages. On receiving any
    // provenance elements, it is passed to all storages.
    @Override
    public void putVertex(AbstractVertex incomingVertex) {
        for (AbstractStorage storage : storages) {
            if (storage.putVertex(incomingVertex)) {
                storage.vertexCount++;
            }
        }
        for (AbstractSketch sketch : sketches) {
            sketch.putVertex(incomingVertex);
        }
    }

    @Override
    public void putEdge(AbstractEdge incomingEdge) {
        for (AbstractStorage storage : storages) {
            if (storage.putEdge(incomingEdge)) {
                storage.edgeCount++;
            }
        }
        for (AbstractSketch sketch : sketches) {
            sketch.putEdge(incomingEdge);
        }
    }
}

class FinalTransformer extends AbstractFilter {

    // This transformer is the last one in the list so any vertices or edges
    // received by it need to be passed to the correct graph.
    @Override
    public void putVertex(AbstractVertex incomingVertex) {
        incomingVertex.resultGraph.commitVertex(incomingVertex);
    }

    @Override
    public void putEdge(AbstractEdge incomingEdge) {
        incomingEdge.resultGraph.commitEdge(incomingEdge);
    }
}

final class NullStream {

    public final static PrintStream out = new PrintStream(new OutputStream() {

        @Override
        public void close() {
        }

        @Override
        public void flush() {
        }

        @Override
        public void write(byte[] b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }

        public void write(int b) {
        }
    });
}

class QueryConnection extends Thread {

    Socket clientSocket;

    public QueryConnection(Socket socket) {
        clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            BufferedReader clientInputReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintStream clientPrintStream = new PrintStream(clientSocket.getOutputStream());
            String queryLine = clientInputReader.readLine();
            Graph resultGraph = Kernel.query(queryLine, false);
            if (resultGraph == null) {
                resultGraph = new Graph();
            }
            ObjectOutputStream clientObjectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
            clientObjectOutputStream.writeObject(resultGraph);
            clientObjectOutputStream.close();
            clientPrintStream.close();
            clientInputReader.close();
            clientSocket.close();
        } catch (Exception ex) {
            Logger.getLogger(QueryConnection.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

class SketchConnection extends Thread {

    Socket clientSocket;

    public SketchConnection(Socket socket) {
        clientSocket = socket;
    }

    @Override
    public void run() {
        try {
            BufferedReader clientInputReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            PrintStream clientPrintStream = new PrintStream(clientSocket.getOutputStream());
            ObjectOutputStream clientObjectOutputStream = new ObjectOutputStream(clientSocket.getOutputStream());
            String queryLine = clientInputReader.readLine();
            if (queryLine.equals("giveSketch")) {
                clientObjectOutputStream.writeObject(Kernel.sketches.iterator().next());
                clientObjectOutputStream.writeObject(Kernel.remoteSketches);
            } else if (queryLine.equals("pathFragment")) {
                ObjectInputStream sketchInputStream = new ObjectInputStream(clientSocket.getInputStream());
                AbstractSketch remoteSketch = (AbstractSketch) sketchInputStream.readObject();
                clientObjectOutputStream.writeObject(Kernel.getPathFragment(remoteSketch));
                sketchInputStream.close();
            } else if (queryLine.startsWith("notifyRebuildSketches")) {
                String tokens[] = queryLine.split("\\s+");
                int currentLevel = Integer.parseInt(tokens[1]);
                int maxLevel = Integer.parseInt(tokens[2]);
                Kernel.notifyRebuildSketches(currentLevel, maxLevel);
            } else if (queryLine.startsWith("propagateSketches")) {
                ObjectInputStream sketchInputStream = new ObjectInputStream(clientSocket.getInputStream());
                Map<String, AbstractSketch> receivedSketches = (Map<String, AbstractSketch>) sketchInputStream.readObject();
                Kernel.remoteSketches.putAll(receivedSketches);
                String tokens[] = queryLine.split("\\s+");
                int currentLevel = Integer.parseInt(tokens[1]);
                int maxLevel = Integer.parseInt(tokens[2]);
                Kernel.propagateSketches(currentLevel, maxLevel);
            }
            clientObjectOutputStream.close();
            clientPrintStream.close();
            clientInputReader.close();
            clientSocket.close();
        } catch (Exception ex) {
            Logger.getLogger(QueryConnection.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
