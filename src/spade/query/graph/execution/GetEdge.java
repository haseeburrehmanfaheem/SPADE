package spade.query.graph.execution;

import spade.core.AbstractEdge;
import spade.core.AbstractVertex;
import spade.core.Graph;
import spade.query.graph.utility.CommonFunctions;
import spade.query.graph.kernel.Environment;
import spade.query.graph.utility.TreeStringSerializable;

import java.util.ArrayList;
import java.util.Set;

public class GetEdge extends Instruction
{
    // Output graph.
    private Graph targetGraph;
    private Graph subjectGraph;
    private String field;
    private String operation;
    private String value;

    public GetEdge(Graph targetGraph, Graph subjectGraph, String field, String operation, String value)
    {
        this.targetGraph = targetGraph;
        this.subjectGraph = subjectGraph;
        this.field = field;
        this.operation = operation;
        this.value = value;
    }

    @Override
    public void execute(Environment env, ExecutionContext ctx)
    {
        Set<AbstractEdge> edgeSet = targetGraph.edgeSet();
        Set<AbstractVertex> vertexSet = targetGraph.vertexSet();
        if(field != null)
        {
            for(AbstractEdge subjectEdge : subjectGraph.edgeSet())
            {
                String subject_value = subjectEdge.getAnnotation(field);
                boolean comparison = CommonFunctions.compareValues(subject_value, value, operation);
                if(comparison)
                {
                    edgeSet.add(subjectEdge);
                    vertexSet.add(subjectEdge.getChildVertex());
                    vertexSet.add(subjectEdge.getParentVertex());
                }
            }
        }
        else
        {
            edgeSet.addAll(subjectGraph.edgeSet());
            vertexSet.addAll(subjectGraph.vertexSet());
        }
        ctx.addResponse(targetGraph);
    }

    @Override
    public String getLabel()
    {
        return "GetEdge";
    }

    @Override
    protected void getFieldStringItems(ArrayList<String> inline_field_names, ArrayList<String> inline_field_values, ArrayList<String> non_container_child_field_names, ArrayList<TreeStringSerializable> non_container_child_fields, ArrayList<String> container_child_field_names, ArrayList<ArrayList<? extends TreeStringSerializable>> container_child_fields)
    {

    }
}