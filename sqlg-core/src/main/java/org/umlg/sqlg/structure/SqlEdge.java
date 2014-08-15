package org.umlg.sqlg.structure;

import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.StringFactory;
import org.apache.commons.lang3.tuple.Pair;

import java.sql.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Date: 2014/07/12
 * Time: 5:41 AM
 */
public class SqlEdge extends SqlElement implements Edge {

    private SqlVertex inVertex;
    private SqlVertex outVertex;

    /**
     * This is called when creating a new edge. from vin.addEdge(label, vout)
     *
     * @param sqlG
     * @param schema
     * @param table
     * @param inVertex
     * @param outVertex
     * @param keyValues
     */
    public SqlEdge(SqlG sqlG, String schema, String table, SqlVertex inVertex, SqlVertex outVertex, Object... keyValues) {
        super(sqlG, schema, table);
        this.inVertex = inVertex;
        this.outVertex = outVertex;
        try {
            insertEdge(keyValues);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public SqlEdge(SqlG sqlG, Long id, String schema, String table, SqlVertex inVertex, SqlVertex outVertex, Object... keyValues) {
        super(sqlG, id, schema, table);
        this.inVertex = inVertex;
        this.outVertex = outVertex;
    }

    public SqlEdge(SqlG sqlG, Long id, String schema, String table) {
        super(sqlG, id, schema, table);
    }

    @Override
    public Iterator<Vertex> vertices(Direction direction) {
        final List<Vertex> vertices = new ArrayList<>();
        if (direction.equals(Direction.OUT) || direction.equals(Direction.BOTH))
            vertices.add(getOutVertex());
        if (direction.equals(Direction.IN) || direction.equals(Direction.BOTH))
            vertices.add(getInVertex());
        return vertices.iterator();
    }

    @Override
    public void remove() {
        this.sqlG.tx().readWrite();
        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes(SchemaManager.EDGES));
        sql.append(" WHERE ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes("ID"));
        sql.append(" = ?");
        if (this.sqlG.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        Connection conn = this.sqlG.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.setLong(1, (Long) this.id());
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        super.remove();
    }

    public SqlVertex getInVertex() {
        if (this.inVertex == null) {
            load();
        }
        return inVertex;
    }

    public SqlVertex getOutVertex() {
        if (this.outVertex == null) {
            load();
        }
        return outVertex;
    }

    @Override
    public String toString() {
        if (this.inVertex == null) {
            load();
        }
        return StringFactory.edgeString(this);
    }

    protected void insertEdge(Object... keyValues) throws SQLException {

        long edgeId = insertGlobalEdge();

        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(this.schema));
        sql.append(".");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(SchemaManager.EDGE_PREFIX + this.table));
        sql.append(" (");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes("ID"));
        sql.append(", ");
        int i = 1;
        List<String> columns = SqlGUtil.transformToInsertColumns(keyValues);
        for (String column : columns) {
            sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(column));
            if (i++ < columns.size()) {
                sql.append(", ");
            }
        }
        if (columns.size() > 0) {
            sql.append(", ");
        }
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(this.inVertex.schema + "." + this.inVertex.table + SqlElement.IN_VERTEX_COLUMN_END));
        sql.append(", ");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(this.outVertex.schema + "." + this.outVertex.table + SqlElement.OUT_VERTEX_COLUMN_END));
        sql.append(") VALUES (?, ");
        i = 1;
        List<String> values = SqlGUtil.transformToInsertValues(keyValues);
        for (String value : values) {
            sql.append("?");
            if (i++ < values.size()) {
                sql.append(", ");
            }
        }
        if (values.size() > 0) {
            sql.append(", ");
        }
        sql.append("?, ?");
        sql.append(")");
        if (this.sqlG.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        i = 1;
        Connection conn = this.sqlG.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.setLong(i++, edgeId);
            i = setKeyValuesAsParameter(i, conn, preparedStatement, keyValues);
            preparedStatement.setLong(i++, this.inVertex.primaryKey);
            preparedStatement.setLong(i++, this.outVertex.primaryKey);
            preparedStatement.executeUpdate();
            this.primaryKey = edgeId;
        }
    }

    private long insertGlobalEdge() throws SQLException {
        long edgeId;
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes(SchemaManager.EDGES));
        sql.append(" (");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes("EDGE_SCHEMA"));
        sql.append(", ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes("EDGE_TABLE"));
        sql.append(") VALUES (?, ?)");
        if (this.sqlG.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        Connection conn = this.sqlG.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString(), Statement.RETURN_GENERATED_KEYS)) {
            preparedStatement.setString(1, this.schema);
            preparedStatement.setString(2, this.table);
            preparedStatement.executeUpdate();
            ResultSet generatedKeys = preparedStatement.getGeneratedKeys();
            if (generatedKeys.next()) {
                edgeId = generatedKeys.getLong(1);
            } else {
                throw new RuntimeException("Could not retrieve the id after an insert into " + SchemaManager.EDGES);
            }
        }
        return edgeId;
    }

    @Override
    protected Object[] load() {
        List<Object> keyValues = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(this.schema));
        sql.append(".");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(SchemaManager.EDGE_PREFIX + this.table));
        sql.append(" WHERE ");
        sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes("ID"));
        sql.append(" = ?");
        if (this.sqlG.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        Connection conn = this.sqlG.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.setLong(1, this.primaryKey);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                Pair<String, String> inVertexColumnName = null;
                Pair<String, String> outVertexColumnName = null;
                ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
                for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
                    String columnName = resultSetMetaData.getColumnName(i);
                    Object o = resultSet.getObject(columnName);
                    if (!columnName.equals("ID") &&
                            !Objects.isNull(o) &&
                            !columnName.endsWith(SqlElement.OUT_VERTEX_COLUMN_END) &&
                            !columnName.endsWith(SqlElement.IN_VERTEX_COLUMN_END)) {

                        keyValues.add(columnName);

                        int type = resultSetMetaData.getColumnType(i);
                        switch (type) {
                            case Types.SMALLINT:
                                keyValues.add(((Integer) o).shortValue());
                                break;
                            case Types.TINYINT:
                                keyValues.add(((Integer) o).byteValue());
                                break;
                            case Types.ARRAY:
                                Array array = (Array) o;
                                int baseType = array.getBaseType();
                                Object[] objectArray = (Object[]) array.getArray();
                                keyValues.add(convertObjectArrayToPrimitiveArray(objectArray, baseType));
                                break;
                            default:
                                keyValues.add(o);
                        }

                    }
                    if (columnName.endsWith(SqlElement.IN_VERTEX_COLUMN_END)) {
                        inVertexColumnName = SqlGUtil.parseLabel(columnName, this.sqlG.getSqlDialect().getPublicSchema());
                    } else if (columnName.endsWith(SqlElement.OUT_VERTEX_COLUMN_END)) {
                        outVertexColumnName = SqlGUtil.parseLabel(columnName, this.sqlG.getSqlDialect().getPublicSchema());
                    }
                }
                if (inVertexColumnName == null || outVertexColumnName == null) {
                    throw new IllegalStateException("in or out vertex id not set!!!!");
                }
                Long inId = resultSet.getLong(inVertexColumnName.getLeft() + "." + inVertexColumnName.getRight());
                Long outId = resultSet.getLong(outVertexColumnName.getLeft() + "." + outVertexColumnName.getRight());

                this.inVertex = new SqlVertex(this.sqlG, inId, inVertexColumnName.getLeft(), inVertexColumnName.getRight().replace(SqlElement.IN_VERTEX_COLUMN_END, ""));
                this.outVertex = new SqlVertex(this.sqlG, outId, outVertexColumnName.getLeft(), outVertexColumnName.getRight().replace(SqlElement.OUT_VERTEX_COLUMN_END, ""));
                break;
            }
            return keyValues.toArray();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
