/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2018 SRI International

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
package spade.query.postgresql.execution;

import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

import spade.core.Graph;
import spade.query.graph.execution.ExecutionContext;
import spade.query.graph.execution.Instruction;
import spade.query.graph.kernel.Environment;
import spade.query.postgresql.types.LongType;
import spade.query.postgresql.types.StringType;
import spade.query.postgresql.utility.PostgresUtil;
import spade.query.postgresql.utility.ResultTable;
import spade.query.postgresql.utility.Schema;
import spade.query.graph.utility.TreeStringSerializable;

/**
 * List all existing graphs in the storage.
 */
public class ListGraphs extends Instruction
{
    private String style;

    public ListGraphs(String style)
    {
        this.style = style;
    }

    @Override
    public void execute(Environment env, ExecutionContext ctx)
    {
        ResultTable table = new ResultTable();

        Map<String, Graph> symbols = env.getSymbols();
        for(Entry<String, Graph> entry : symbols.entrySet())
        {
            String symbol = entry.getKey();
            if(symbol.startsWith("$"))
            {
                addSymbol(symbol, entry.getValue(), table);
            }
        }
        addSymbol("$base", Environment.kBaseGraph, table);

        Schema schema = new Schema();
        schema.addColumn("Graph Name", StringType.GetInstance());
        if(!style.equals("name"))
        {
            schema.addColumn("Number of Vertices", LongType.GetInstance());
            schema.addColumn("Number of Edges", LongType.GetInstance());
            if(style.equals("detail"))
            {
                schema.addColumn("Start Time", LongType.GetInstance());
                schema.addColumn("End Time", LongType.GetInstance());
            }
        }
        table.setSchema(schema);

        ctx.addResponse(table.toString());
    }

    private void addSymbol(String symbol, Graph graph, ResultTable table)
    {
        ResultTable.Row row = new ResultTable.Row();
        row.add(symbol);
        if(!style.equals("name"))
        {
            row.add(PostgresUtil.getNumVertices(graph));
            row.add(PostgresUtil.getNumEdges(graph));
        }
        table.addRow(row);
    }

    @Override
    public String getLabel()
    {
        return "ListGraphs";
    }

    @Override
    protected void getFieldStringItems(
            ArrayList<String> inline_field_names,
            ArrayList<String> inline_field_values,
            ArrayList<String> non_container_child_field_names,
            ArrayList<TreeStringSerializable> non_container_child_fields,
            ArrayList<String> container_child_field_names,
            ArrayList<ArrayList<? extends TreeStringSerializable>> container_child_fields)
    {
        inline_field_names.add("style");
        inline_field_values.add(style);
    }
}