package cn.edu.ruc.iir.pard.executor.connector.node;

import cn.edu.ruc.iir.pard.sql.tree.ComparisonExpression;
import static com.google.common.base.MoreObjects.toStringHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * pard
 *
 * @author guodong
 */
public class JoinNode
        extends CartesianNode
{
    private static final long serialVersionUID = 3355047142533066940L;
    private Set<String> joinSet;
    private List<ComparisonExpression> exprList;
    private String otherInfo;

    public JoinNode()
    {
        name = "JOIN";
        joinSet = new HashSet<String>();
        exprList = new ArrayList<ComparisonExpression>();
    }

    public JoinNode(JoinNode node)
    {
        super(node);
        name = "JOIN";
        joinSet = new HashSet<String>();
        joinSet.addAll(node.joinSet);
        exprList = new ArrayList<ComparisonExpression>();
        exprList.addAll(node.getExprList());
        childrens.clear();
        childrens.addAll(node.getJoinChildren());
    }

    public boolean hasChildren()
    {
        return !childrens.isEmpty();
    }

    public void addJoinChild(PlanNode node)
    {
        this.childrens.add(node);
    }

    public List<PlanNode> getJoinChildren()
    {
        return childrens;
    }

    public Set<String> getJoinSet()
    {
        return joinSet;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", "JOIN")
                .add("children", childrens)
                .add("joinSet", this.joinSet)
                .add("exprList", this.exprList)
                .toString();
    }

    public String getOtherInfo()
    {
        return otherInfo;
    }

    public void setOtherInfo(String otherInfo)
    {
        this.otherInfo = otherInfo;
    }

    public List<ComparisonExpression> getExprList()
    {
        return exprList;
    }
}
