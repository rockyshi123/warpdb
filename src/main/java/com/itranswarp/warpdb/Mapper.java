package com.itranswarp.warpdb;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.AttributeConverter;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PostRemove;
import javax.persistence.PostUpdate;
import javax.persistence.PrePersist;
import javax.persistence.PreRemove;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.Version;

import com.itranswarp.warpdb.util.NameUtils;

interface Getter {
	Object get(Object bean) throws IllegalAccessException, InvocationTargetException;
}

interface Setter {
	void set(Object bean, Object value) throws IllegalAccessException, InvocationTargetException;
}

class AccessibleProperty {
	// Field or Method:
	final AccessibleObject accessible;

	// java type:
	final Class<?> propertyType;

	// converter:
	final AttributeConverter<Object, Object> converter;

	// java bean property name:
	final String propertyName;

	// table column name:
	final String columnName;

	// column DDL:
	final String columnDefinition;

	// getter function:
	final Getter getter;

	// setter function:
	final Setter setter;

	boolean isId() {
		return this.accessible.isAnnotationPresent(Id.class);
	}

	boolean isVersion() {
		boolean isVersion = this.accessible.isAnnotationPresent(Version.class);
		if (isVersion) {
			if (!VERSION_TYPES.contains(this.propertyType)) {
				throw new RuntimeException("Unsupported @Version type: " + this.propertyType.getName());
			}
		}
		return isVersion;
	}

	boolean isInsertable() {
		if (isId()) {
			GeneratedValue gv = this.accessible.getAnnotation(GeneratedValue.class);
			if (gv != null && gv.strategy() == GenerationType.IDENTITY) {
				return false;
			}
		}
		Column col = this.accessible.getAnnotation(Column.class);
		return col == null || col.insertable();
	}

	boolean isUpdatable() {
		if (isId()) {
			return false;
		}
		Column col = this.accessible.getAnnotation(Column.class);
		return col == null || col.updatable();
	}

	public AccessibleProperty(Field f) {
		this(f.getType(), f.getName(), f, (obj) -> {
			return f.get(obj);
		}, (obj, value) -> {
			f.set(obj, value);
		});
	}

	public AccessibleProperty(String name, Method getter, Method setter) {
		this(getter.getReturnType(), name, getter, (obj) -> {
			return getter.invoke(obj);
		}, (obj, value) -> {
			setter.invoke(obj, value);
		});
	}

	private AccessibleProperty(Class<?> type, String propertyName, AccessibleObject accessible, Getter getter,
			Setter setter) {
		accessible.setAccessible(true);
		this.accessible = accessible;
		this.propertyType = checkPropertyType(type);
		this.converter = getConverter(accessible);
		this.propertyName = propertyName;
		this.columnName = getColumnName(accessible, propertyName);
		this.columnDefinition = getColumnDefinition(accessible, propertyType);
		this.getter = getter;
		this.setter = setter;
	}

	@SuppressWarnings("unchecked")
	private AttributeConverter<Object, Object> getConverter(AccessibleObject accessible) {
		Convert converter = accessible.getAnnotation(Convert.class);
		if (converter != null) {
			Class<?> converterClass = converter.converter();
			if (!converterClass.isAssignableFrom(AttributeConverter.class)) {
				throw new RuntimeException(
						"Converter class must be AttributeConverter rather than " + converterClass.getName());
			}
			try {
				return (AttributeConverter<Object, Object>) converterClass.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				throw new RuntimeException("Cannot instantiate Converter: " + converterClass.getName(), e);
			}
		}
		return null;
	}

	private static Class<?> checkPropertyType(Class<?> type) {
		if (DEFAULT_COLUMN_TYPES.containsKey(type)) {
			return type;
		}
		throw new RuntimeException("Unsupported type: " + type);
	}

	private static String getColumnName(AccessibleObject ao, String defaultName) {
		Column col = ao.getAnnotation(Column.class);
		if (col == null || col.name().isEmpty()) {
			return defaultName;
		}
		return col.name();
	}

	private static String getColumnDefinition(AccessibleObject ao, Class<?> type) {
		Column col = ao.getAnnotation(Column.class);
		if (col == null || col.columnDefinition().isEmpty()) {
			return getDefaultColumnType(type, col);
		}
		return col.columnDefinition();
	}

	private static String getDefaultColumnType(Class<?> type, Column col) {
		String ddl = DEFAULT_COLUMN_TYPES.get(type);
		if (ddl.equals("VARCHAR($1)")) {
			ddl = ddl.replace("$1", String.valueOf(col == null ? 255 : col.length()));
		}
		if (ddl.equals("DECIMAL($1,$2)")) {
			int preci = col == null ? 0 : col.precision();
			int scale = col == null ? 0 : col.scale();
			if (preci == 0) {
				preci = 10;
			}
			ddl = ddl.replace("$1", String.valueOf(preci)).replace("$2", String.valueOf(scale));
		}
		return ddl;
	}

	static final Map<Class<?>, String> DEFAULT_COLUMN_TYPES = new HashMap<>();

	static final Set<Class<?>> VERSION_TYPES = new HashSet<>();

	static {
		DEFAULT_COLUMN_TYPES.put(String.class, "VARCHAR($1)");

		DEFAULT_COLUMN_TYPES.put(boolean.class, "BIT");
		DEFAULT_COLUMN_TYPES.put(Boolean.class, "BIT");

		DEFAULT_COLUMN_TYPES.put(byte.class, "TINYINT");
		DEFAULT_COLUMN_TYPES.put(Byte.class, "TINYINT");
		DEFAULT_COLUMN_TYPES.put(short.class, "SMALLINT");
		DEFAULT_COLUMN_TYPES.put(Short.class, "SMALLINT");
		DEFAULT_COLUMN_TYPES.put(int.class, "INTEGER");
		DEFAULT_COLUMN_TYPES.put(Integer.class, "INTEGER");
		DEFAULT_COLUMN_TYPES.put(long.class, "BIGINT");
		DEFAULT_COLUMN_TYPES.put(Long.class, "BIGINT");
		DEFAULT_COLUMN_TYPES.put(float.class, "REAL");
		DEFAULT_COLUMN_TYPES.put(Float.class, "REAL");
		DEFAULT_COLUMN_TYPES.put(double.class, "DOUBLE");
		DEFAULT_COLUMN_TYPES.put(Double.class, "DOUBLE");

		DEFAULT_COLUMN_TYPES.put(BigDecimal.class, "DECIMAL($1,$2)");
		DEFAULT_COLUMN_TYPES.put(java.sql.Date.class, "DATE");
		DEFAULT_COLUMN_TYPES.put(LocalDate.class, "DATE");
		DEFAULT_COLUMN_TYPES.put(LocalTime.class, "TIME");
		DEFAULT_COLUMN_TYPES.put(java.util.Date.class, "DATETIME");
		DEFAULT_COLUMN_TYPES.put(java.sql.Timestamp.class, "TIMESTAMP");

		DEFAULT_COLUMN_TYPES.put(java.sql.Blob.class, "BLOB");
		DEFAULT_COLUMN_TYPES.put(java.sql.Clob.class, "CLOB");

		VERSION_TYPES.addAll(Arrays.asList(long.class, Long.class, int.class, Integer.class, short.class, Short.class,
				java.sql.Timestamp.class));
	}

}

class Mapper<T> {

	final Class<T> entityClass;
	final String tableName;

	// @Id property:
	final AccessibleProperty id;
	// @Version property:
	final AccessibleProperty version;

	// all properties including @Id, key is property name (NOT column name)
	final List<AccessibleProperty> properties;

	final List<AccessibleProperty> insertableProperties;
	final List<AccessibleProperty> updatableProperties;
	final Map<String, AccessibleProperty> updatablePropertiesMap;

	final BeanRowMapper<T> rowMapper;

	final String querySQL;
	final String insertSQL;
	final String updateSQL;
	final String deleteSQL;

	final Listener prePersist;
	final Listener preUpdate;
	final Listener preRemove;
	final Listener postLoad;
	final Listener postPersist;
	final Listener postUpdate;
	final Listener postRemove;

	public Mapper(Class<T> clazz) {
		super();
		List<AccessibleProperty> all = getPropertiesIncludeHierarchy(clazz);
		// check duplicate name:
		Set<String> propertyNamesSet = new HashSet<>();
		for (String propertyName : all.stream().map((p) -> {
			return p.propertyName;
		}).toArray(String[]::new)) {
			if (!propertyNamesSet.add(propertyName.toLowerCase())) {
				throw new ConfigurationException(
						"Duplicate property name found: " + propertyName + " in class: " + clazz.getName());
			}
		}
		Set<String> columnNamesSet = new HashSet<>();
		for (String columnName : all.stream().map((p) -> {
			return p.columnName;
		}).toArray(String[]::new)) {
			if (!columnNamesSet.add(columnName.toLowerCase())) {
				throw new ConfigurationException("Duplicate column name found: " + columnName);
			}
		}
		// check @Id:
		AccessibleProperty[] ids = all.stream().filter((p) -> {
			return p.isId();
		}).toArray(AccessibleProperty[]::new);
		if (ids.length == 0) {
			throw new ConfigurationException("No @Id found.");
		}
		if (ids.length > 1) {
			throw new ConfigurationException("Multiple @Id found.");
		}
		// get @Version:
		AccessibleProperty[] versions = all.stream().filter((p) -> {
			return p.isVersion();
		}).toArray(AccessibleProperty[]::new);
		if (versions.length > 1) {
			throw new ConfigurationException("Multiple @Version found.");
		}
		this.version = versions.length == 0 ? null : versions[0];

		this.properties = all;

		this.insertableProperties = all.stream().filter((p) -> {
			return p.isInsertable();
		}).collect(Collectors.toList());

		this.updatableProperties = all.stream().filter((p) -> {
			return p.isUpdatable();
		}).collect(Collectors.toList());

		this.updatablePropertiesMap = buildPropertiesMap(this.updatableProperties);

		// init:
		this.id = ids[0];
		this.entityClass = clazz;
		this.tableName = getTableName(clazz);

		this.querySQL = "SELECT * FROM " + this.tableName + " WHERE " + this.id.columnName + " = ?";

		this.insertSQL = "INSERT INTO " + this.tableName + " ("
				+ String.join(", ", this.insertableProperties.stream().map((p) -> {
					return p.columnName;
				}).toArray(String[]::new)) + ") VALUES (" + numOfQuestions(this.insertableProperties.size()) + ")";

		this.updateSQL = "UPDATE " + this.tableName + " SET "
				+ String.join(", ", this.updatableProperties.stream().map((p) -> {
					return p.columnName + " = ?";
				}).toArray(String[]::new)) + " WHERE " + this.id.columnName + " = ?";

		this.deleteSQL = "DELETE FROM " + this.tableName + " WHERE " + this.id.columnName + " = ?";

		this.rowMapper = new BeanRowMapper<>(this.entityClass, this.properties);

		List<Method> methods = this.findMethods(clazz);
		this.prePersist = findListener(methods, PrePersist.class);
		this.preUpdate = findListener(methods, PreUpdate.class);
		this.preRemove = findListener(methods, PreRemove.class);
		this.postLoad = findListener(methods, PostLoad.class);
		this.postPersist = findListener(methods, PostPersist.class);
		this.postUpdate = findListener(methods, PostUpdate.class);
		this.postRemove = findListener(methods, PostRemove.class);
	}

	Map<String, AccessibleProperty> buildPropertiesMap(List<AccessibleProperty> props) {
		Map<String, AccessibleProperty> map = new HashMap<>();
		for (AccessibleProperty prop : props) {
			map.put(prop.propertyName, prop);
		}
		return map;
	}

	Listener findListener(List<Method> methods, Class<? extends Annotation> anno) {
		Method target = null;
		for (Method m : methods) {
			if (m.isAnnotationPresent(anno)) {
				if (target == null) {
					target = m;
				} else {
					throw new ConfigurationException("Found multiple @" + anno.getSimpleName());
				}
			}
		}
		if (target == null) {
			return EMPTY_LISTENER;
		}
		// check target:
		if (target.getParameterTypes().length > 0) {
			throw new ConfigurationException("Invalid listener method: " + target.getName() + ". Expect zero args.");
		}
		if (Modifier.isStatic(target.getModifiers())) {
			throw new ConfigurationException("Invalid listener method: " + target.getName() + ". Cannot be static.");
		}
		target.setAccessible(true);
		Method listener = target;
		return (obj) -> {
			listener.invoke(obj);
		};
	}

	static final Listener EMPTY_LISTENER = new Listener() {
		@Override
		public void invoke(Object obj) throws IllegalAccessException, InvocationTargetException {
		}
	};

	List<Method> findMethods(Class<T> clazz) {
		List<Method> list = new ArrayList<>(50);
		findMethodsIncludeHierarchy(clazz, list);
		return list;
	}

	void findMethodsIncludeHierarchy(Class<?> clazz, List<Method> methods) {
		Method[] ms = clazz.getDeclaredMethods();
		for (Method m : ms) {
			methods.add(m);
		}
		if (clazz.getSuperclass() != Object.class) {
			findMethodsIncludeHierarchy(clazz.getSuperclass(), methods);
		}
	}

	String numOfQuestions(int n) {
		String[] qs = new String[n];
		return String.join(", ", Arrays.stream(qs).map((s) -> {
			return "?";
		}).toArray(String[]::new));
	}

	String getTableName(Class<?> clazz) {
		Table table = clazz.getAnnotation(Table.class);
		if (table != null && !table.name().isEmpty()) {
			return table.name();
		}
		return NameUtils.toCamelCaseName(clazz.getSimpleName());
	}

	List<AccessibleProperty> getPropertiesIncludeHierarchy(Class<?> clazz) {
		List<AccessibleProperty> properties = new ArrayList<>();
		addFieldPropertiesIncludeHierarchy(clazz, properties);
		// find methods:
		List<AccessibleProperty> foundMethods = Arrays.stream(clazz.getMethods()).filter((m) -> {
			int mod = m.getModifiers();
			// exclude @Transient:
			if (m.isAnnotationPresent(Transient.class)) {
				return false;
			}
			// exclude static:
			if (Modifier.isStatic(mod)) {
				return false;
			}
			// exclude getClass():
			if (m.getName().equals("getClass")) {
				return false;
			}
			// check if getter:
			if (m.getParameterTypes().length > 0) {
				return false;
			}
			if (m.getName().startsWith("get") && m.getName().length() >= 4) {
				return true;
			}
			if (m.getName().startsWith("is") && m.getName().length() >= 3
					&& (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
				return true;
			}
			return false;
		}).map((getter) -> {
			Class<?> type = getter.getReturnType();
			String name;
			if (getter.getName().startsWith("get")) {
				name = Character.toLowerCase(getter.getName().charAt(3)) + getter.getName().substring(4);
			} else {
				// isXxx()
				name = Character.toLowerCase(getter.getName().charAt(2)) + getter.getName().substring(3);
			}
			// find setter:
			String setterName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
			Method setter;
			try {
				setter = clazz.getMethod(setterName, type);
			} catch (NoSuchMethodException e) {
				throw new ConfigurationException(e);
			} catch (SecurityException e) {
				throw new RuntimeException(e);
			}
			return new AccessibleProperty(name, getter, setter);
		}).collect(Collectors.toList());
		properties.addAll(foundMethods);
		return properties;
	}

	void addFieldPropertiesIncludeHierarchy(Class<?> clazz, List<AccessibleProperty> collector) {
		List<AccessibleProperty> foundFields = Arrays.stream(clazz.getDeclaredFields()).filter((f) -> {
			int mod = f.getModifiers();
			// exclude @Transient:
			if (f.isAnnotationPresent(Transient.class)) {
				return false;
			}
			// exclude final, static:
			if (Modifier.isFinal(mod) || Modifier.isStatic(mod)) {
				return false;
			}
			return true;
		}).map((f) -> {
			return new AccessibleProperty(f);
		}).collect(Collectors.toList());
		collector.addAll(foundFields);
		if (clazz.getSuperclass() != Object.class) {
			addFieldPropertiesIncludeHierarchy(clazz.getSuperclass(), collector);
		}
	}

}