package org.umlg.sqlg.structure;

import com.tinkerpop.gremlin.structure.Element;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Property;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Date: 2014/07/12
 * Time: 5:40 AM
 */
public abstract class SqlgElement implements Element {

    public static final String IN_VERTEX_COLUMN_END = "_IN_ID";
    public static final String OUT_VERTEX_COLUMN_END = "_OUT_ID";
    private Logger logger = LoggerFactory.getLogger(SqlgVertex.class.getName());

    protected String schema;
    protected String table;
    protected final SqlG sqlG;
    protected long primaryKey;
    protected Map<String, Object> properties = new HashMap<>();

    public SqlgElement(SqlG sqlG, String schema, String table) {
        this.sqlG = sqlG;
        this.schema = schema;
        this.table = table;
    }

    public SqlgElement(SqlG sqlG, Long id, String label) {
        this.sqlG = sqlG;
        this.primaryKey = id;
        SchemaTable schemaTable = SqlgUtil.parseLabel(label, this.sqlG.getSqlDialect().getPublicSchema());
        this.schema = schemaTable.getSchema();
        this.table = schemaTable.getTable();
    }

    public SqlgElement(SqlG sqlG, Long id, String schema, String table) {
        this.sqlG = sqlG;
        this.primaryKey = id;
        this.schema = schema;
        this.table = table;
    }

    SchemaTable getSchemaTable() {
        return SchemaTable.of(this.getSchema(), this.getTable());
    }

    public void setInternalPrimaryKey(Long id) {
        this.primaryKey = id;
    }

    @Override
    public Object id() {
        return primaryKey;
    }

    @Override
    public String label() {
        return this.table;
    }

    @Override
    public void remove() {
        StringBuilder sql = new StringBuilder("DELETE FROM ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes(this.schema));
        sql.append(".");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes((this instanceof Vertex ? SchemaManager.VERTEX_PREFIX : SchemaManager.EDGE_PREFIX) + this.table));
        sql.append(" WHERE ");
        sql.append(this.sqlG.getSchemaManager().getSqlDialect().maybeWrapInQoutes("ID"));
        sql.append(" = ?");
        if (this.sqlG.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = this.sqlG.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.setLong(1, (Long) this.id());
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<String> keys() {
        this.sqlG.tx().readWrite();
        return this.internalGetProperties().keySet();
    }

    @Override
    public Set<String> hiddenKeys() {
        this.sqlG.tx().readWrite();
        return this.internalGetHiddens().keySet();
    }

    //TODO relook at hiddens, unnecessary looping and queries
    @Override
    public <V> Property<V> property(String key) {
        Property property = internalGetProperties().get(key);
        if (property == null) {
            //try hiddens
            property = internalGetHiddens().get(Graph.Key.unHide(key));
            if (property == null) {
                return emptyProperty();
            } else {
                return property;
            }
        } else {
            return property;
        }
    }

    protected Property emptyProperty() {
        return Property.empty();
    }

    @Override
    public <V> Property<V> property(String key, V value) {
        ElementHelper.validateProperty(key, value);
        this.sqlG.getSqlDialect().validateProperty(key, value);
        //Validate the property
        PropertyType.from(value);
        //Check if column exist
        this.sqlG.getSchemaManager().ensureColumnExist(
                this.schema,
                this instanceof Vertex ? SchemaManager.VERTEX_PREFIX + this.table : SchemaManager.EDGE_PREFIX + this.table,
                ImmutablePair.of(key, PropertyType.from(value)));
        updateRow(key, value);
        return instantiateProperty(key, value);
    }

    protected <V> SqlgProperty<V> instantiateProperty(String key, V value) {
        return new SqlgProperty<>(this.sqlG, this, key, value);
    }

    /**
     * load the row from the db and caches the results in the element
     */
    protected abstract void load();

    public String getSchema() {
        return schema;
    }

    public String getTable() {
        return table;
    }

    private void updateRow(String key, Object value) {

        boolean elementInInsertedCache = false;
        if (this.sqlG.features().supportsBatchMode() && this.sqlG.tx().isInBatchMode()) {
            elementInInsertedCache = this.sqlG.tx().getBatchManager().updateProperty(this, key, value);
        }

        if (!elementInInsertedCache) {
            StringBuilder sql = new StringBuilder("UPDATE ");
            sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(this.schema));
            sql.append(".");
            sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes((this instanceof Vertex ? SchemaManager.VERTEX_PREFIX : SchemaManager.EDGE_PREFIX) + this.table));
            sql.append(" SET ");
            sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes(key));
            sql.append(" = ?");
            sql.append(" WHERE ");
            sql.append(this.sqlG.getSqlDialect().maybeWrapInQoutes("ID"));
            sql.append(" = ?");
            if (this.sqlG.getSqlDialect().needsSemicolon()) {
                sql.append(";");
            }
            Connection conn = this.sqlG.tx().getConnection();
            try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                Map<String, Object> keyValue = new HashMap<>();
                keyValue.put(key, value);
                setKeyValuesAsParameter(this.sqlG, 1, conn, preparedStatement, keyValue);
                preparedStatement.setLong(2, (Long) this.id());
                preparedStatement.executeUpdate();
                preparedStatement.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        //Cache the properties
        this.properties.put(key, value);
    }

    @Override
    public boolean equals(final Object object) {
        if (this.sqlG.features().supportsBatchMode() && this.sqlG.tx().isInBatchMode()) {
            return super.equals(object);
        } else {
            return ElementHelper.areEqual(this, object);
        }
    }

    @Override
    public int hashCode() {
        if (this.sqlG.features().supportsBatchMode() && this.sqlG.tx().isInBatchMode()) {
            return super.hashCode();
        } else {
            return this.id().hashCode();
        }
    }

    protected Object convertObjectArrayToPrimitiveArray(Object[] value, int baseType) {
        if (value instanceof String[]) {
            return value;
        }
        switch (baseType) {
            case Types.BIT:
                return copy(value, new boolean[value.length]);
            case Types.BOOLEAN:
                return copy(value, new boolean[value.length]);
            case Types.TINYINT:
                return copyToTinyInt((Integer[]) value, new byte[value.length]);
            case Types.SMALLINT:
                return copyToSmallInt(value, new short[value.length]);
            case Types.INTEGER:
                return copy(value, new int[value.length]);
            case Types.BIGINT:
                return copy(value, new long[value.length]);
            case Types.REAL:
                return copy(value, new float[value.length]);
            case Types.DOUBLE:
                return copy(value, new double[value.length]);
            case Types.VARCHAR:
                return copy(value, new String[value.length]);
        }
        if (value instanceof Integer[]) {
            switch (baseType) {
                case Types.TINYINT:
                    return copyToTinyInt((Integer[]) value, new byte[value.length]);
                case Types.SMALLINT:
                    return copyToSmallInt((Integer[]) value, new short[value.length]);
                default:
                    return copy(value, new int[value.length]);
            }
        }
        if (value instanceof Long[]) {
            return copy(value, new long[value.length]);
        }
        if (value instanceof Double[]) {
            return copy(value, new double[value.length]);
        }
        if (value instanceof Float[]) {
            return copy(value, new float[value.length]);
        }
        if (value instanceof Boolean[]) {
            return copy(value, new boolean[value.length]);
        }
        if (value instanceof Character[]) {
            return copy(value, new char[value.length]);
        }
        throw new IllegalArgumentException(
                String.format("%s[] is not a supported property value type",
                        value.getClass().getComponentType().getName()));

    }

    private <T> T copy(Object[] value, T target) {
        for (int i = 0; i < value.length; i++) {
            if (value[i] == null) {
                throw new IllegalArgumentException("Property array value elements may not be null.");
            }
            Array.set(target, i, value[i]);
        }
        return target;
    }

    private <T> T copyToTinyInt(Integer[] value, T target) {
        for (int i = 0; i < value.length; i++) {
            if (value[i] == null) {
                throw new IllegalArgumentException("Property array value elements may not be null.");
            }
            Array.set(target, i, value[i].byteValue());
        }
        return target;
    }

    private <T> T copyToSmallInt(Object[] value, T target) {
        for (int i = 0; i < value.length; i++) {
            if (value[i] == null) {
                throw new IllegalArgumentException("Property array value elements may not be null.");
            }
            Array.set(target, i, ((Number) value[i]).shortValue());
        }
        return target;
    }

    public static int setKeyValuesAsParameter(SqlG sqlG, int i, Connection conn, PreparedStatement preparedStatement, Map<String, Object> keyValues) throws SQLException {
        for (ImmutablePair<PropertyType, Object> pair : SqlgUtil.transformToTypeAndValue(keyValues)) {
            switch (pair.left) {
                case BOOLEAN:
                    preparedStatement.setBoolean(i++, (Boolean) pair.right);
                    break;
                case BYTE:
                    preparedStatement.setByte(i++, (Byte) pair.right);
                    break;
                case SHORT:
                    preparedStatement.setShort(i++, (Short) pair.right);
                    break;
                case INTEGER:
                    preparedStatement.setInt(i++, (Integer) pair.right);
                    break;
                case LONG:
                    preparedStatement.setLong(i++, (Long) pair.right);
                    break;
                case FLOAT:
                    preparedStatement.setFloat(i++, (Float) pair.right);
                    break;
                case DOUBLE:
                    preparedStatement.setDouble(i++, (Double) pair.right);
                    break;
                case STRING:
                    preparedStatement.setString(i++, (String) pair.right);
                    break;

                //TODO the array properties are hardcoded according to postgres's jdbc driver
                case BOOLEAN_ARRAY:
                    java.sql.Array booleanArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.BOOLEAN_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, booleanArray);
                    break;
                case BYTE_ARRAY:
                    preparedStatement.setBytes(i++, (byte[]) pair.right);
                    break;
                case SHORT_ARRAY:
                    java.sql.Array shortArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.SHORT_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, shortArray);
                    break;
                case INTEGER_ARRAY:
                    java.sql.Array intArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.INTEGER_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, intArray);
                    break;
                case LONG_ARRAY:
                    java.sql.Array longArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.LONG_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, longArray);
                    break;
                case FLOAT_ARRAY:
                    java.sql.Array floatArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.FLOAT_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, floatArray);
                    break;
                case DOUBLE_ARRAY:
                    java.sql.Array doubleArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.DOUBLE_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, doubleArray);
                    break;
                case STRING_ARRAY:
                    java.sql.Array stringArray = conn.createArrayOf(sqlG.getSqlDialect().getArrayDriverType(PropertyType.STRING_ARRAY), SqlgUtil.transformArrayToInsertValue(pair.left, pair.right));
                    preparedStatement.setArray(i++, stringArray);
                    break;
                default:
                    throw new IllegalStateException("Unhandled type " + pair.left.name());
            }
        }
        return i;
    }

    /**
     * @return All non hidden keys
     */
    //TODO caching???
    protected <V> Map<String, ? extends Property<V>> internalGetProperties(final String... propertyKeys) {
        return internalGetProperties(false, propertyKeys);
    }

    protected <V> Map<String, ? extends Property<V>> internalGetProperties(boolean hidden, final String... propertyKeys) {
        this.sqlG.tx().readWrite();
        load();
        Map<String, SqlgProperty<V>> properties = new HashMap<>();
        for (String key : this.properties.keySet()) {
            if (propertyKeys.length == 0 || Arrays.binarySearch(propertyKeys, key) >= 0) {
                Object value = this.properties.get(key);
                if (!key.equals("ID") && value != null) {
                    if (hidden && Graph.Key.isHidden(key)) {
                        properties.put(Graph.Key.unHide(key), instantiateProperty(Graph.Key.unHide(key), (V) value));
                    } else if (!hidden && !Graph.Key.isHidden(key)) {
                        properties.put(key, instantiateProperty(key, (V) value));
                    } else {
                        //ignore
                    }
                }
            }
        }
        return properties;
    }

    protected <V> Map<String, ? extends Property<V>> internalGetHiddens(final String... propertyKeys) {
        this.sqlG.tx().readWrite();
        return internalGetProperties(true, propertyKeys);
    }

    protected class Iterators implements Element.Iterators {

        @Override
        public <V> Iterator<? extends Property<V>> properties(final String... propertyKeys) {
            SqlgElement.this.sqlG.tx().readWrite();
            return SqlgElement.this.<V>internalGetProperties(propertyKeys).values().iterator();
        }

        @Override
        public <V> Iterator<? extends Property<V>> hiddens(final String... propertyKeys) {
            SqlgElement.this.sqlG.tx().readWrite();

            // make sure all keys request are hidden - the nature of Graph.Key.hide() is to not re-hide a hidden key
            final String[] hiddenKeys = Stream.of(propertyKeys).map(Graph.Key::hide)
                    .collect(Collectors.toList()).toArray(new String[propertyKeys.length]);

            return SqlgElement.this.<V>internalGetHiddens(propertyKeys).values().iterator();
        }

    }

}
