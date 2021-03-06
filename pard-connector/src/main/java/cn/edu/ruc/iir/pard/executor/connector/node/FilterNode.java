package cn.edu.ruc.iir.pard.executor.connector.node;

import cn.edu.ruc.iir.pard.sql.tree.Expression;

import static com.google.common.base.MoreObjects.toStringHelper;

/**
 * pard
 *
 * @author guodong
 */
public class FilterNode
        extends PlanNode
{
    private static final long serialVersionUID = 8695368351559462220L;
    private Expression expression;

    public FilterNode(Expression expression)
    {
        this.name = "FILTER";
        this.expression = expression;
    }
    public FilterNode(FilterNode node)
    {
        super(node);
        this.name = "FILTER";
        this.expression = node.expression;
    }
    public Expression getExpression()
    {
        return expression;
    }
    public void setExpression(Expression expression)
    {
        this.expression = expression;
    }
    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", "FILTER")
                .add("expression", expression)
                .add("child", getLeftChild())
                .toString();
    }
}
