package cn.edu.ruc.iir.pard.scheduler;

import cn.edu.ruc.iir.pard.catalog.Column;
import cn.edu.ruc.iir.pard.catalog.Schema;
import cn.edu.ruc.iir.pard.catalog.Site;
import cn.edu.ruc.iir.pard.catalog.Table;
import cn.edu.ruc.iir.pard.commons.utils.DataType;
import cn.edu.ruc.iir.pard.commons.utils.RowConstructor;
import cn.edu.ruc.iir.pard.communication.rpc.PardRPCClient;
import cn.edu.ruc.iir.pard.etcd.dao.SchemaDao;
import cn.edu.ruc.iir.pard.etcd.dao.SiteDao;
import cn.edu.ruc.iir.pard.exchange.PardExchangeClient;
import cn.edu.ruc.iir.pard.exchange.PardFileExchangeClient;
import cn.edu.ruc.iir.pard.executor.connector.Block;
import cn.edu.ruc.iir.pard.executor.connector.CreateSchemaTask;
import cn.edu.ruc.iir.pard.executor.connector.CreateTableTask;
import cn.edu.ruc.iir.pard.executor.connector.DeleteTask;
import cn.edu.ruc.iir.pard.executor.connector.DropSchemaTask;
import cn.edu.ruc.iir.pard.executor.connector.DropTableTask;
import cn.edu.ruc.iir.pard.executor.connector.InsertIntoTask;
import cn.edu.ruc.iir.pard.executor.connector.LoadTask;
import cn.edu.ruc.iir.pard.executor.connector.PardResultSet;
import cn.edu.ruc.iir.pard.executor.connector.QueryTask;
import cn.edu.ruc.iir.pard.executor.connector.Task;
import cn.edu.ruc.iir.pard.executor.connector.node.NodeHelper;
import cn.edu.ruc.iir.pard.executor.connector.node.PlanNode;
import cn.edu.ruc.iir.pard.executor.connector.node.TableScanNode;
import cn.edu.ruc.iir.pard.executor.connector.node.UnionNode;
import cn.edu.ruc.iir.pard.planner.Plan;
import cn.edu.ruc.iir.pard.planner.ddl.SchemaCreationPlan;
import cn.edu.ruc.iir.pard.planner.ddl.SchemaDropPlan;
import cn.edu.ruc.iir.pard.planner.ddl.SchemaShowPlan;
import cn.edu.ruc.iir.pard.planner.ddl.TableCreationPlan;
import cn.edu.ruc.iir.pard.planner.ddl.TableDropPlan;
import cn.edu.ruc.iir.pard.planner.ddl.TableShowPlan;
import cn.edu.ruc.iir.pard.planner.ddl.UsePlan;
import cn.edu.ruc.iir.pard.planner.dml.DeletePlan;
import cn.edu.ruc.iir.pard.planner.dml.InsertPlan;
import cn.edu.ruc.iir.pard.planner.dml.LoadPlan;
import cn.edu.ruc.iir.pard.planner.dml.QueryPlan;
import cn.edu.ruc.iir.pard.server.PardStartupHook;
import cn.edu.ruc.iir.pard.sql.expr.Expr;
import cn.edu.ruc.iir.pard.sql.tree.Expression;
import cn.edu.ruc.iir.pard.sql.tree.Row;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * pard task scheduler.
 * server level.
 *
 * This is just a simple execution controller for job.
 * Cannot do scheduling at all ...
 *
 * @author guodong
 */
public class TaskScheduler
        implements PardStartupHook
{
    private final Logger logger = Logger.getLogger(TaskScheduler.class.getName());
    private final SiteDao siteDao;

    private TaskScheduler()
    {
        this.siteDao = new SiteDao();
    }

    @Override
    public void startup() throws RuntimeException
    {
        this.INSTANCE();
    }

    private static final class TaskSchedulerHolder
    {
        private static final TaskScheduler instance = new TaskScheduler();
    }

    public static final TaskScheduler INSTANCE()
    {
        return TaskSchedulerHolder.instance;
    }

    // todo this sucks, full of if else
    public List<Task> generateTasks(Plan plan)
    {
        Set<String> sites = siteDao.listNodes().keySet();

        // use plan
        if (plan instanceof UsePlan) {
            logger.info("Task generation for use plan");
            return ImmutableList.of();
        }

        // schema creation plan
        if (plan instanceof SchemaCreationPlan) {
            logger.info("Task generation for schema creation plan");
            List<Task> tasks = new ArrayList<>();
            SchemaCreationPlan schemaCreationPlan = (SchemaCreationPlan) plan;
            int index = 0;
            for (String site : sites) {
                CreateSchemaTask task = new CreateSchemaTask(
                        schemaCreationPlan.getSchemaName(),
                        schemaCreationPlan.isNotExists(),
                        site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // schema drop plan
        if (plan instanceof SchemaDropPlan) {
            logger.info("Task generation for schema drop plan");
            List<Task> tasks = new ArrayList<>();
            SchemaDropPlan schemaDropPlan = (SchemaDropPlan) plan;
            int index = 0;
            for (String site : sites) {
                DropSchemaTask task = new DropSchemaTask(schemaDropPlan.getSchemaName(),
                        schemaDropPlan.isExists(),
                        site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // table creation plan
        if (plan instanceof TableCreationPlan) {
            logger.info("Task generation for table creation plan");
            List<Task> tasks = new ArrayList<>();
            TableCreationPlan tableCreationPlan = (TableCreationPlan) plan;
            if (tableCreationPlan.isAlreadyDone()) {
                return ImmutableList.of();
            }
            Map<String, List<Column>> partitionMap = tableCreationPlan.getDistributionHints();
            String tableName = tableCreationPlan.getTableName();
            String schemaName = tableCreationPlan.getSchemaName();
            boolean isNotExists = tableCreationPlan.isNotExists();
            int index = 0;
            for (String site : partitionMap.keySet()) {
                List<Column> columns = partitionMap.get(site);
                CreateTableTask task = new CreateTableTask(
                        schemaName,
                        tableName,
                        isNotExists,
                        columns,
                        site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // table drop plan
        if (plan instanceof TableDropPlan) {
            logger.info("Task generation for table drop plan");
            List<Task> tasks = new ArrayList<>();
            TableDropPlan tableDropPlan = (TableDropPlan) plan;
            String schemaName = tableDropPlan.getSchemaName();
            String tableName = tableDropPlan.getTableName();
            Set<String> siteNames = tableDropPlan.getDistributionHints().keySet();
            int index = 0;
            for (String sn : siteNames) {
                Task task = new DropTableTask(schemaName, tableName, sn);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // show schemas
        if (plan instanceof SchemaShowPlan) {
            return ImmutableList.of();
        }

        // show tables
        if (plan instanceof TableShowPlan) {
            return ImmutableList.of();
        }

        // load
        if (plan instanceof LoadPlan) {
            LoadPlan loadPlan = (LoadPlan) plan;
            String schemaName = loadPlan.getSchemaName();
            String tableName = loadPlan.getTableName();
            Map<String, List<String>> distributionHints = ((LoadPlan) plan).getDistributionHints();
            List<Task> tasks = new ArrayList<>();
            int index = 0;
            for (String site : distributionHints.keySet()) {
                Task task = new LoadTask(schemaName, tableName, distributionHints.get(site), site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // insert plan
        if (plan instanceof InsertPlan) {
            logger.info("Task generation for insert plan");
            List<Task> tasks = new ArrayList<>();
            InsertPlan insertPlan = (InsertPlan) plan;
            Map<String, List<Row>> partitionMap = insertPlan.getDistributionHints();
            String tableName = insertPlan.getTableName();
            String schemaName = insertPlan.getSchemaName();
            int index = 0;
            for (String site : partitionMap.keySet()) {
                List<Column> columns = insertPlan.getColListMap().get(site);
                int columnSize = columns.size();
                List<Row> rows = partitionMap.get(site);
                int rowSize = rows.size();
                String[][] rowsStr = new String[rowSize][];
                int rowIndex = 0;
                for (Row row : rows) {
                    String[] rowStr = new String[columnSize];
                    int colIndex = 0;
                    for (Expression expression : row.getItems()) {
                        rowStr[colIndex] = expression.toString();
                        colIndex++;
                    }
                    rowsStr[rowIndex] = rowStr;
                    rowIndex++;
                }
                InsertIntoTask task = new InsertIntoTask(schemaName, tableName, columns, rowsStr, site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        if (plan instanceof DeletePlan) {
            logger.info("Task generation for delete plan");
            DeletePlan deletePlan = (DeletePlan) plan;
            Map<String, Expr> distributionHints = deletePlan.getDistributionHints();
            List<Task> tasks = new ArrayList<>();
            int index = 0;
            for (String site : distributionHints.keySet()) {
                DeleteTask task = new DeleteTask(
                        deletePlan.getSchemaName(),
                        deletePlan.getTableName(),
                        distributionHints.get(site).toExpression(),
                        site);
                task.setTaskId(plan.getJobId() + "-" + index);
                tasks.add(task);
                index++;
            }
            return ImmutableList.copyOf(tasks);
        }

        // query plan
        if (plan instanceof QueryPlan) {
            logger.info("Task generation for query plan");
            try {
                QueryPlan queryPlan = (QueryPlan) plan;
                PlanNode planNode = queryPlan.getPlan();
                PlanNode currentNode = planNode;
                UnionNode internalUnionNode = null;
                while (currentNode.hasChildren()) {
                    currentNode = currentNode.getLeftChild();
                    if (currentNode instanceof UnionNode) {
                        internalUnionNode = (UnionNode) currentNode;
                        break;
                    }
                }
                if (internalUnionNode == null) {
                    return ImmutableList.of(new QueryTask(planNode));
                }
                List<Task> tasks = new ArrayList<>();
                List<PlanNode> unionChildren = internalUnionNode.getUnionChildren();
                int index = 0;
                for (PlanNode childNode : unionChildren) {
                    internalUnionNode.setChildren(childNode, true, false);
                    PlanNode node = childNode;
                    TableScanNode tableScanNode = null;
                    if (node instanceof TableScanNode) {
                        tableScanNode = (TableScanNode) node;
                    }
                    while (!(node instanceof TableScanNode) && node.hasChildren()) {
                        if (node.getLeftChild() instanceof TableScanNode) {
                            tableScanNode = (TableScanNode) node.getLeftChild();
                            break;
                        }
                        node = node.getLeftChild();
                    }
                    if (tableScanNode == null) {
                        return null;
                    }
                    QueryTask task = new QueryTask(tableScanNode.getSite(), NodeHelper.copyNode(planNode));
                    task.setTaskId(plan.getJobId() + "-" + index);
                    tasks.add(task);
                    index++;
                }
                return ImmutableList.copyOf(tasks);
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    // todo this sucks, full of if else
    public PardResultSet executeJob(Job job)
    {
        logger.info("Executing job[" + job.getJobId() + "]");
        SiteDao siteDao = new SiteDao();

        if (job.getJobState() != JobScheduler.JobState.EXECUTING) {
            logger.log(Level.WARNING, "Job[" + job.getJobId() + "] is in not in executing state");
            return new PardResultSet(PardResultSet.ResultStatus.EXECUTING_ERR);
        }

        Plan plan = job.getPlan();
        List<Task> tasks = job.getTasks();

        // job does not need remote tasks
        if (tasks.isEmpty()) {
            logger.info("Job[" + job.getJobId() + "] has empty task list");
            // show schemas
            if (plan instanceof SchemaShowPlan) {
                SchemaDao schemaDao = new SchemaDao();
                Set<String> schemas = schemaDao.listAll();
                Column header = new Column(0, DataType.VARCHAR.getType(), "schema", 100, 0, 0);
                PardResultSet resultSet = new PardResultSet(PardResultSet.ResultStatus.OK, ImmutableList.of(header));
                for (String schemaName : schemas) {
                    RowConstructor rowConstructor = new RowConstructor();
                    rowConstructor.appendString(schemaName);
                    resultSet.add(rowConstructor.build());
                }
                return resultSet;
            }
            // show tables
            if (plan instanceof TableShowPlan) {
                SchemaDao schemaDao = new SchemaDao();
                Schema schema = schemaDao.loadByName(((TableShowPlan) plan).getSchema());
                List<Table> tables = schema.getTableList();
                Column header = new Column(0, DataType.VARCHAR.getType(), "table", 100, 0, 0);
                PardResultSet resultSet = new PardResultSet(PardResultSet.ResultStatus.OK, ImmutableList.of(header));
                for (Table table : tables) {
                    RowConstructor rowConstructor = new RowConstructor();
                    rowConstructor.appendString(table.getTablename());
                    resultSet.add(rowConstructor.build());
                }
                return resultSet;
            }
            // I don't know who will come here currently, just keep it
            if (plan.afterExecution(true)) {
                return PardResultSet.okResultSet;
            }
        }
        else {
            // load
            if (plan instanceof LoadPlan) {
                LoadPlan loadPlan = (LoadPlan) plan;
                String path = loadPlan.getPath();
                Map<String, Task> taskMap = new HashMap<>();
                ConcurrentLinkedQueue<PardResultSet> resultSets = new ConcurrentLinkedQueue<>();
                // distribute file
                for (Task task : tasks) {
                    String site = task.getSite();
                    Site nodeSite = siteDao.listNodes().get(site);
                    if (nodeSite == null) {
                        logger.info("No corresponding node " + site + " found for execution.");
                        continue;
                    }
                    PardFileExchangeClient exchangeClient = new PardFileExchangeClient(
                                    nodeSite.getIp(),
                                    nodeSite.getFileExchangePort(),
                                    path,
                                    ((LoadPlan) plan).getSchemaName(),
                                    ((LoadPlan) plan).getTableName(),
                                    task.getTaskId(),
                                    resultSets);
                    exchangeClient.run();
                    taskMap.put(task.getTaskId(), task);
                }
                while (!taskMap.isEmpty()) {
                    PardResultSet resultSet = resultSets.poll();
                    if (resultSet == null) {
                        continue;
                    }
                    taskMap.remove(resultSet.getTaskId());
                    if (resultSet.getStatus() != PardResultSet.ResultStatus.OK) {
                        return PardResultSet.execErrResultSet;
                    }
                }
                return PardResultSet.okResultSet;
            }

            // distribute query result and collect
            // this is a simplest implementation
            // todo collected result set form exchange client shall be passed on for next query stage
            if (plan instanceof QueryPlan) {
                logger.info("Executing query tasks for job[" + job.getJobId() + "]");
                PardResultSet resultSet = new PardResultSet();
                Map<String, Task> taskMap = new HashMap<>();
                ConcurrentLinkedQueue<Block> blocks = new ConcurrentLinkedQueue<>();
                for (Task task : tasks) {
                    String site = task.getSite();
                    String taskId = task.getTaskId();
                    Site nodeSite = siteDao.listNodes().get(site);
                    if (nodeSite != null) {
                        PardExchangeClient client = new PardExchangeClient(nodeSite.getIp(), nodeSite.getExchangePort());
                        client.connect(task, blocks);
                        taskMap.put(taskId, task);
                    }
                }
                // wait for all tasks done
                while (!taskMap.isEmpty()) {
                    Block block = blocks.poll();
                    if (block == null) {
                        logger.info("Waiting for more blocks...");
                        continue;
                    }
                    resultSet.addBlock(block);
                    logger.info("Added block " + block.getSequenceId() + ", num of rows: " + block.getRows().size());
                    if (!block.isSequenceHasNext()) {
                        String taskId = block.getTaskId();
                        taskMap.remove(taskId);
                        logger.info("Task " + taskId + " done.");
                    }
                }
                plan.afterExecution(true);
                return resultSet;
            }

            // delete
            if (plan instanceof DeletePlan) {
                Map<String, Task> taskMap = new HashMap<>();
                ConcurrentLinkedQueue<Block> blocks = new ConcurrentLinkedQueue<>();
                for (Task task : tasks) {
                    String site = task.getSite();
                    Site nodeSite = siteDao.listNodes().get(site);
                    if (nodeSite == null) {
                        logger.info("No corresponding node " + site + " found for execution.");
                        continue;
                    }
                    PardExchangeClient client = new PardExchangeClient(nodeSite.getIp(), nodeSite.getExchangePort());
                    client.connect(task, blocks);
                    taskMap.put(task.getTaskId(), task);
                }
                // wait for all tasks done
                while (!taskMap.isEmpty()) {
                    Block block = blocks.poll();
                    if (block == null) {
                        logger.info("Waiting for more blocks...");
                        continue;
                    }
                    if (!block.isSequenceHasNext()) {
                        String taskId = block.getTaskId();
                        taskMap.remove(taskId);
                        logger.info("Task " + taskId + " done.");
                    }
                }
                return PardResultSet.okResultSet;
            }

            // rpc task
            else {
                List<Integer> statusL = new ArrayList<>();
                for (Task task : tasks) {
                    String site = task.getSite();
                    Site nodeSite = siteDao.listNodes().get(site);
                    if (nodeSite == null) {
                        logger.info("No corresponding node " + site + " found for execution.");
                        continue;
                    }
                    PardRPCClient client = new PardRPCClient(nodeSite.getIp(), nodeSite.getRpcPort());
                    // create schema task
                    if (task instanceof CreateSchemaTask) {
                        logger.info("Calling schema creation");
                        int status = client.createSchema((CreateSchemaTask) task);
                        statusL.add(status);
                        client.shutdown();
                    }
                    // drop schema task
                    if (task instanceof DropSchemaTask) {
                        logger.info("Calling schema drop");
                        int status = client.dropSchema((DropSchemaTask) task);
                        statusL.add(status);
                        client.shutdown();
                    }
                    // create table task
                    if (task instanceof CreateTableTask) {
                        logger.info("Calling table creation");
                        int status = client.createTable((CreateTableTask) task);
                        statusL.add(status);
                        client.shutdown();
                    }
                    // drop table task
                    if (task instanceof DropTableTask) {
                        logger.info("Calling task drop");
                        int status = client.dropTable((DropTableTask) task);
                        statusL.add(status);
                        client.shutdown();
                    }
                    // insert task
                    if (task instanceof InsertIntoTask) {
                        logger.info("Calling insert");
                        int status = client.insertInto((InsertIntoTask) task);
                        statusL.add(status);
                        client.shutdown();
                    }
                }
                for (int status : statusL) {
                    if (status <= 0) {
                        logger.info("Check task execution status. Wrong status " + status + " found.");
                        return new PardResultSet(PardResultSet.ResultStatus.EXECUTING_ERR);
                    }
                }
            }
            if (!plan.afterExecution(true)) {
                logger.info("After execution failed!");
                return new PardResultSet(PardResultSet.ResultStatus.EXECUTING_ERR);
            }
        }
        return new PardResultSet(PardResultSet.ResultStatus.OK);
    }
}
