package cn.edu.ruc.iir.pard.planner.ddl;

import cn.edu.ruc.iir.pard.catalog.Schema;
import cn.edu.ruc.iir.pard.commons.exception.ErrorMessage;
import cn.edu.ruc.iir.pard.commons.exception.ErrorMessage.ErrCode;
import cn.edu.ruc.iir.pard.etcd.dao.SchemaDao;
import cn.edu.ruc.iir.pard.sql.tree.DropSchema;
import cn.edu.ruc.iir.pard.sql.tree.Statement;

public class SchemaDropPlan
        extends SchemaPlan
{
    public SchemaDropPlan(Statement stmt)
    {
        super(stmt);
    }

    private DropSchema stmt;
    private String schemaName;
    private boolean exists;
    //TODO: How to process cascade.
    private boolean cascade;
    private Schema schema;
    @Override
    public ErrorMessage semanticAnalysis()
    {
        Statement statement = this.getStatment();
        if (!(statement instanceof DropSchema)) {
            return ErrorMessage.throwMessage(ErrCode.ParseError, "Drop Schema Statement");
        }
        stmt = (DropSchema) statement;
        schemaName = stmt.getSchemaName().toString();
        exists = stmt.isExists();
        cascade = stmt.isCascade();
        SchemaDao dao = new SchemaDao();
        schema = dao.loadByName(schemaName);
        if (schema == null) {
            if (exists) {
                // if exists, drop;
                alreadyDone = true;
                return ErrorMessage.getOKMessage();
            }
            else {
                return ErrorMessage.throwMessage(ErrCode.SchemaNotExsits, schemaName);
            }
        }
        return ErrorMessage.getOKMessage();
    }

    @Override
    public boolean beforeExecution()
    {
        return true;
    }

    @Override
    public boolean afterExecution(boolean executeSuccess)
    {
        SchemaDao dao = new SchemaDao();
        if (!alreadyDone) {
            return dao.drop(schemaName);
        }
        return true;
    }

    public DropSchema getStmt()
    {
        return stmt;
    }

    public String getSchemaName()
    {
        return schemaName;
    }

    public boolean isExists()
    {
        return exists;
    }

    public Schema getSchema()
    {
        return schema;
    }
}
