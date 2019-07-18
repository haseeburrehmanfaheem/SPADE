package spade.query.graph.utility;

import org.apache.commons.lang3.math.NumberUtils;
import spade.core.AbstractEdge;
import spade.core.AbstractStorage;
import spade.core.AbstractVertex;
import spade.core.Edge;
import spade.core.Graph;
import spade.core.Vertex;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static spade.core.AbstractQuery.currentStorage;
import static spade.query.graph.utility.CommonVariables.CHILD_VERTEX_KEY;
import static spade.query.graph.utility.CommonVariables.EDGE_TABLE;
import static spade.query.graph.utility.CommonVariables.PARENT_VERTEX_KEY;
import static spade.query.graph.utility.CommonVariables.PRIMARY_KEY;
import static spade.query.graph.utility.CommonVariables.VERTEX_TABLE;

public class CommonFunctions
{
    private static Logger logger = Logger.getLogger(CommonFunctions.class.getName());

    public static boolean compareValues(String subject_value_str, String value_str, String operation)
    {
        boolean comparison = false;
        if(subject_value_str != null)
        {
            boolean isNumeric = NumberUtils.isParsable(value_str);
            if(isNumeric)
            {
                Double value_to_compare = NumberUtils.createDouble(value_str);
                Double subject_value = NumberUtils.createDouble(subject_value_str);
                switch(operation)
                {
                    case "=":
                        if(subject_value_str.equals(value_str))
                            comparison = true;
                        break;
                    case "<>":
                        if(!subject_value_str.equals(value_str))
                            comparison = true;
                        break;
                    //TODO: implement robust double comparison using threshold
                    case "<":
                        if(subject_value < value_to_compare)
                            comparison = true;
                        break;
                    case "<=":
                        if(subject_value <= value_to_compare)
                            comparison = true;
                        break;
                    case ">":
                        if(subject_value > value_to_compare)
                            comparison = true;
                        break;
                    case ">=":
                        if(subject_value >= value_to_compare)
                            comparison = true;
                        break;
                    default:

                }
            }
            else
            {
                switch(operation)
                {
                    case "LIKE":
                        // escape all 12 special characters: [](){}.*+?$^|#\
                        value_str = value_str.replaceAll(
                                "(\\[|\\]|\\(|\\)|\\{|\\}|\\.|\\*|\\+|\\?|\\$|\\^|\\||\\#|\\\\)",
                                "\\\\$1");
                        // Reassuring note: this dangerous-looking regular expression has been well tested.

                        // convert SQL's LIKE syntax into java regular expression syntax
                        value_str = value_str.replace("_", ".").replace("%", ".*");
                        Pattern pattern = Pattern.compile(value_str);
                        if(pattern.matcher(value_str).matches())
                        {
                            comparison = true;
                        }
                        break;
                    case "REGEXP":
                        // coming up soon.
                        // TODO: requires conversion between POSIX regex and JAVA regex
                        break;
                }
            }
        }
        return comparison;
    }

    public static void executeGetVertex(Set<AbstractVertex> vertexSet, String getVertexQuery)
    {
        logger.log(Level.INFO, "Executing query: " + getVertexQuery);
        ResultSet result = (ResultSet) currentStorage.executeQuery(getVertexQuery);
        ResultSetMetaData metadata;
        try
        {
            metadata = result.getMetaData();
            int columnCount = metadata.getColumnCount();

            Map<Integer, String> columnLabels = new HashMap<>();
            for(int i = 1; i <= columnCount; i++)
            {
                columnLabels.put(i, metadata.getColumnName(i));
            }

            while(result.next())
            {
                AbstractVertex vertex = new Vertex();
                for(int i = 1; i <= columnCount; i++)
                {
                    String colName = columnLabels.get(i);
                    String value = result.getString(i);
                    if(value != null)
                    {
                        if(colName != null && !colName.equals(PRIMARY_KEY))
                        {
                            vertex.addAnnotation(colName, value);
                        }
                    }
                }
                vertexSet.add(vertex);
            }
        }
        catch(SQLException ex)
        {
            logger.log(Level.SEVERE, "Error executing GetVertex Query", ex);
        }
    }

    public static void executeGetEdge(Graph targetGraph, String getEdgeQuery, boolean getEndPoints)
    {
        Set<AbstractEdge> targetEdgeSet = targetGraph.edgeSet();
        Set<AbstractVertex> targetVertexSet = targetGraph.vertexSet();
        logger.log(Level.INFO, "Executing query: " + getEdgeQuery);
        ResultSet result = (ResultSet) currentStorage.executeQuery(getEdgeQuery);
        ResultSetMetaData metadata;
        try
        {
            metadata = result.getMetaData();
            int columnCount = metadata.getColumnCount();

            Map<Integer, String> columnLabels = new HashMap<>();
            for(int i = 1; i <= columnCount; i++)
            {
                columnLabels.put(i, metadata.getColumnName(i));
            }

            while(result.next())
            {
                AbstractEdge edge = new Edge(null, null);
                for(int i = 1; i <= columnCount; i++)
                {
                    String colName = columnLabels.get(i);
                    String value = result.getString(i);
                    if(value != null)
                    {
                        if(colName != null && !colName.equals(AbstractStorage.PRIMARY_KEY))
                        {
                            if(getEndPoints && colName.equals(CHILD_VERTEX_KEY))
                            {
                                String getChildQuery = "SELECT * FROM " + VERTEX_TABLE + " WHERE \""
                                        + PRIMARY_KEY + "\" = '" + value + "'";
                                Set<AbstractVertex> childVertexSet = new HashSet<>();
                                executeGetVertex(childVertexSet, getChildQuery);
                                AbstractVertex childVertex = childVertexSet.iterator().next();
                                edge.setChildVertex(childVertex);
                                targetVertexSet.add(childVertex);
                            }
                            if(getEndPoints && colName.equals(PARENT_VERTEX_KEY))
                            {
                                String getParentQuery = "SELECT * FROM " + VERTEX_TABLE + " WHERE \""
                                        + PRIMARY_KEY + "\" = '" + value + "'";
                                Set<AbstractVertex> parentVertexSet = new HashSet<>();
                                executeGetVertex(parentVertexSet, getParentQuery);
                                AbstractVertex parentVertex = parentVertexSet.iterator().next();
                                edge.setParentVertex(parentVertex);
                                targetVertexSet.add(parentVertex);
                            }
                            edge.addAnnotation(colName, value);
                        }
                    }
                }
                targetEdgeSet.add(edge);
            }
        }
        catch(SQLException ex)
        {
            logger.log(Level.SEVERE, "Error executing GetEdge Query", ex);
        }
    }

    public static void getAllVertexEdges(Graph targetGraph, StringBuilder childVertexHashes, StringBuilder parentVertexHashes)
    {
        if(childVertexHashes.length() <= 0 || parentVertexHashes.length() <= 0)
        {
            logger.log(Level.WARNING, "Either children or parents are absent!");
            return;
        }
        StringBuilder getEdgesQuery = new StringBuilder(500);
        getEdgesQuery.append("SELECT * FROM ");
        getEdgesQuery.append(EDGE_TABLE);
        getEdgesQuery.append(" WHERE \"");
        getEdgesQuery.append(CHILD_VERTEX_KEY);
        getEdgesQuery.append("\" IN (");
        getEdgesQuery.append(childVertexHashes.substring(0, childVertexHashes.length() - 2));
        getEdgesQuery.append(") AND \"");
        getEdgesQuery.append(PARENT_VERTEX_KEY);
        getEdgesQuery.append("\" IN (");
        getEdgesQuery.append(parentVertexHashes.substring(0, parentVertexHashes.length() - 2));
        getEdgesQuery.append(")");

        //TODO: avoid duplication of database access when called from getLineage
        executeGetEdge(targetGraph, getEdgesQuery.toString(), true);
    }
}