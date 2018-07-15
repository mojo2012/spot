package at.spot.core.infrastructure.instrumentation;

import java.io.File;
import java.io.IOException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.OrderColumn;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import at.spot.core.infrastructure.annotation.ItemType;
import at.spot.core.infrastructure.annotation.Property;
import at.spot.core.infrastructure.annotation.Relation;
import at.spot.core.infrastructure.type.RelationNodeType;
import at.spot.core.infrastructure.type.RelationType;
import at.spot.instrumentation.ClassTransformer;
import at.spot.instrumentation.transformer.AbstractBaseClassTransformer;
import at.spot.instrumentation.transformer.IllegalClassTransformationException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import javassist.CtClass;
import javassist.CtField;
import javassist.NotFoundException;
import javassist.bytecode.ConstPool;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.AnnotationMemberValue;
import javassist.bytecode.annotation.ArrayMemberValue;
import javassist.bytecode.annotation.BooleanMemberValue;
import javassist.bytecode.annotation.ClassMemberValue;
import javassist.bytecode.annotation.EnumMemberValue;
import javassist.bytecode.annotation.MemberValue;
import javassist.bytecode.annotation.StringMemberValue;

/**
 * Transforms custom {@link ItemType} annotations to JPA entity annotations.
 */
@ClassTransformer
public class JpaEntityClassTransformer extends AbstractBaseClassTransformer {

	private static final Logger LOG = LoggerFactory.getLogger(JpaEntityClassTransformer.class);

	protected static final String MV_CASCADE = "cascade";
	protected static final String MV_NODE_TYPE = "nodeType";
	protected static final String MV_REFERENCED_COLUMN_NAME = "referencedColumnName";
	protected static final String MV_PK = "pk";
	protected static final String MV_INVERSE_JOIN_COLUMNS = "inverseJoinColumns";
	protected static final String MV_JOIN_COLUMNS = "joinColumns";
	protected static final String MV_NAME = "name";
	protected static final String MV_RELATION_NAME = "relationName";
	protected static final String MV_PERSISTABLE = "persistable";
	protected static final String CLASS_FILE_SUFFIX = ".class";
	protected static final String MV_MAPPED_BY = "mappedBy";
	protected static final String MV_MAPPED_TO = "mappedTo";
	protected static final String MV_TYPE = "type";
	protected static final String MV_TYPE_CODE = "typeCode";
	protected static final String MV_UNIQUE = "unique";
	protected static final String MV_NULLABLE = "nullable";
	protected static final String MV_COLUMN_NAMES = "columnNames";
	protected static final String MV_UNIQUE_CONSTRAINTS = "uniqueConstraints";
	protected static final String RELATION_SOURCE_COLUMN = "source_pk";
	protected static final String RELATION_TARGET_COLUMN = "target_pk";

	@SuppressFBWarnings({ "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", "REC_CATCH_EXCEPTION" })
	@Override
	protected Optional<CtClass> transform(final ClassLoader loader, final CtClass clazz,
			final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain)
			throws IllegalClassTransformationException {

		try {
			// we only want to transform item types only ...
			if (!clazz.isFrozen() && isItemType(clazz) && !alreadyTransformed(clazz)) {

				// add JPA entity annotation
				addEntityAnnotation(clazz);

				// process item properties
				for (final CtField field : getDeclaredFields(clazz)) {
					if (!clazz.equals(field.getDeclaringClass())) {
						continue;
					}

					final Optional<Annotation> propertyAnn = getAnnotation(field, Property.class);

					// process item type property annotation
					if (propertyAnn.isPresent()) {
						// create the necessary JPA annotations based on
						// Relation and Property
						// annotations
						final List<Annotation> fieldAnnotations = createJpaRelationAnnotations(clazz, field,
								propertyAnn.get());

						// only add column annotation if there is no relation
						// annotation, as this is not allowed
						if (CollectionUtils.isEmpty(fieldAnnotations)) {
							// add column annotation used hold infos about
							// unique constraints
							final Optional<Annotation> columnAnn = createColumnAnnotation(clazz, field,
									propertyAnn.get());

							if (columnAnn.isPresent()) {
								fieldAnnotations.add(columnAnn.get());
							}
						}

						// and add them to the clazz
						addAnnotations(clazz, field, fieldAnnotations);
					}
				}

				// create unique constraints
				{
					final Set<String> fieldNamesForUniqueConstraint = new HashSet<>();

					CtClass currentClass = clazz;

					while (currentClass != null) {
						Map<CtField, Annotation> properties = Stream.of(currentClass.getFields()).filter(f -> {
							try {
								return f.getAnnotation(Property.class) != null;
							} catch (ClassNotFoundException e) {
								LOG.warn(e.getMessage());
							}

							return false;
						}).collect(Collectors.toMap(Function.identity(), f -> getAnnotation(f, Property.class).get()));

						for (Map.Entry<CtField, Annotation> propertyField : properties.entrySet()) {
							BooleanMemberValue unique = (BooleanMemberValue) propertyField.getValue()
									.getMemberValue(MV_UNIQUE);
							if (unique != null && unique.getValue()) {
								fieldNamesForUniqueConstraint.add(propertyField.getKey().getName());
							}
						}

						currentClass = currentClass.getSuperclass();
					}

					if (fieldNamesForUniqueConstraint.size() > 0) {
						ConstPool pool = clazz.getClassFile2().getConstPool();

						// @Table
						final Annotation tableAnnotation = createAnnotation(pool, Table.class);
						// @Table(uniqueConstraints = { ... })
						final ArrayMemberValue constraintsArrayVal = new ArrayMemberValue(pool);
						tableAnnotation.addMemberValue("uniqueConstraints", constraintsArrayVal);

						final AnnotationMemberValue actualUniqueConstraintVal = new AnnotationMemberValue(pool);
						Annotation uniqueConstraintsAnnotation = createAnnotation(pool, UniqueConstraint.class);
						actualUniqueConstraintVal.setValue(uniqueConstraintsAnnotation);
						constraintsArrayVal.setValue(new MemberValue[] { actualUniqueConstraintVal });

						ArrayMemberValue columns = new ArrayMemberValue(pool);
						uniqueConstraintsAnnotation.addMemberValue("columnNames", columns);

						// add column names
						columns.setValue(fieldNamesForUniqueConstraint.stream().map(c -> {
							StringMemberValue colVal = new StringMemberValue(pool);
							colVal.setValue(c);
							return colVal;
						}).collect(Collectors.toList()).toArray(new MemberValue[] {}));

						addAnnotations(clazz, Arrays.asList(tableAnnotation));
					}
				}

				try {
					final File file = new File("/var/tmp/" + clazz.getName() + CLASS_FILE_SUFFIX);

					if (file.exists()) {
						file.delete();
					}

					writeClass(clazz, file);
				} catch (final IOException e) {
					throw new IllegalClassTransformationException(
							String.format("Unable to write class file %s", clazz.getName()), e);
				}

				return Optional.of(clazz);
			}
		} catch (final Exception e) {
			LOG.error(e.getMessage(), e);

			throw new IllegalClassTransformationException(
					String.format("Unable to process JPA annotations for class file %s", clazz.getName()), e);
		}

		return Optional.empty();
	}

	protected boolean alreadyTransformed(final CtClass clazz) throws IllegalClassTransformationException {
		final Optional<Annotation> entityAnnotation = getAnnotation(clazz, Entity.class);

		return entityAnnotation.isPresent();
	}

	protected void addEntityAnnotation(final CtClass clazz) throws IllegalClassTransformationException {
		final Optional<Annotation> itemTypeAnn = getItemTypeAnnotation(clazz);

		if (itemTypeAnn.isPresent()) {
			final BooleanMemberValue val = (BooleanMemberValue) itemTypeAnn.get().getMemberValue(MV_PERSISTABLE);

			if (val != null && val.getValue()) {
				// this type needs a separate deployment table
				addAnnotations(clazz, Arrays.asList(createAnnotation(clazz, Entity.class)));
			} else {
				// this type is not an persistable entity
				addAnnotations(clazz, Arrays.asList(createAnnotation(clazz, MappedSuperclass.class)));
			}
		}
	}

	protected boolean isItemType(final CtClass clazz) throws IllegalClassTransformationException {
		return getItemTypeAnnotation(clazz).isPresent();
	}

	protected Optional<Annotation> getItemTypeAnnotation(final CtClass clazz)
			throws IllegalClassTransformationException {

		// if the given class is a java base class it can't be unfrozen and it
		// would
		// throw an exception
		// so we check for valid classes
		if (isValidClass(clazz.getName())) {
			return getAnnotation(clazz, ItemType.class);
		}

		return Optional.empty();
	}

	protected String getItemTypeCode(final CtClass clazz) throws IllegalClassTransformationException {
		final Optional<Annotation> ann = getItemTypeAnnotation(clazz);

		if (ann.isPresent()) {
			final StringMemberValue typeCode = (StringMemberValue) ann.get().getMemberValue(MV_TYPE_CODE);

			return typeCode.getValue();
		}

		return null;
	}

	protected Optional<Annotation> createColumnAnnotation(final CtClass clazz, final CtField field,
			final Annotation propertyAnnotation) {

		Annotation ann = createAnnotation(field.getFieldInfo2().getConstPool(), Column.class);

		final StringMemberValue columnName = new StringMemberValue(field.getFieldInfo2().getConstPool());
		columnName.setValue(field.getName());
		ann.addMemberValue("name", columnName);

		// final BooleanMemberValue unique = (BooleanMemberValue)
		// propertyAnnotation.getMemberValue(MV_UNIQUE);
		//
		// if (unique != null) {
		// ann.addMemberValue(MV_UNIQUE, unique);
		// }

		return Optional.ofNullable(ann);
	}

	protected List<Annotation> createJpaRelationAnnotations(final CtClass entityClass, final CtField field,
			final Annotation propertyAnnotation) throws NotFoundException, IllegalClassTransformationException {

		final List<Annotation> jpaAnnotations = new ArrayList<>();

		final Optional<Annotation> relAnnotation = getAnnotation(field, Relation.class);

		if (relAnnotation.isPresent()) {
			final EnumMemberValue relType = (EnumMemberValue) relAnnotation.get().getMemberValue(MV_TYPE);

			// JPA Relation annotations
			if (StringUtils.equals(relType.getValue(), RelationType.ManyToMany.toString())) {
				jpaAnnotations.add(createJpaRelationAnnotation(entityClass, field, ManyToMany.class));

				// necessary for serialization
				jpaAnnotations.add(createSerializationAnnotation(entityClass, field,
						"at.spot.core.infrastructure.serialization.jackson.ItemCollectionProxySerializer"));

				// necessary for FETCH JOINS
				jpaAnnotations.add(createOrderedListAnnotation(entityClass, field));

				// JoinTable annotation for bi-directional m-to-n relation table
				jpaAnnotations
						.add(createJoinTableAnnotation(entityClass, field, propertyAnnotation, relAnnotation.get()));

			} else if (StringUtils.equals(relType.getValue(), RelationType.OneToMany.toString())) {
				final Annotation o2mAnn = createJpaRelationAnnotation(entityClass, field, OneToMany.class);
				addMappedByAnnotationValue(field, o2mAnn, entityClass, relAnnotation.get());
				jpaAnnotations.add(o2mAnn);

				// necessary for serialization
				jpaAnnotations.add(createSerializationAnnotation(entityClass, field,
						"at.spot.core.infrastructure.serialization.jackson.ItemCollectionProxySerializer"));

				// necessary for FETCH JOINS
				jpaAnnotations.add(createOrderedListAnnotation(entityClass, field));

			} else if (StringUtils.equals(relType.getValue(), RelationType.ManyToOne.toString())) {
				jpaAnnotations.add(createJpaRelationAnnotation(entityClass, field, ManyToOne.class));
				jpaAnnotations.add(createJoinColumnAnnotation(entityClass, field));

				// necessary for serialization
				jpaAnnotations.add(createSerializationAnnotation(entityClass, field,
						"at.spot.core.infrastructure.serialization.jackson.ItemProxySerializer"));
			} else {
				// one to one in case the field type is a subtype of Item

				jpaAnnotations.add(createJpaRelationAnnotation(entityClass, field, OneToOne.class));
			}

		} else if (isItemType(field.getType())) { // one to one in case the
													// field type is a subtype
													// of Item
			jpaAnnotations.add(createJpaRelationAnnotation(entityClass, field, ManyToOne.class));
			jpaAnnotations.add(createJoinColumnAnnotation(entityClass, field));

			// necessary for serialization
			jpaAnnotations.add(createSerializationAnnotation(entityClass, field,
					"at.spot.core.infrastructure.serialization.jackson.ItemProxySerializer"));
		} else if (hasInterface(field.getType(), Collection.class) || hasInterface(field.getType(), Map.class)) {
			jpaAnnotations.add(createAnnotation(entityClass, ElementCollection.class));

			// necessary for serialization
			jpaAnnotations.add(createSerializationAnnotation(entityClass, field,
					"at.spot.core.infrastructure.serialization.jackson.ItemCollectionProxySerializer"));
		}

		return jpaAnnotations;
	}

	/**
	 * Annotates relation collections with an {@link OrderBy} annotation to make
	 * FETCH JOINS work correctly.
	 */
	protected Annotation createOrderedListAnnotation(final CtClass entityClass, final CtField field)
			throws IllegalClassTransformationException {

		final Annotation jsonSerializeAnn = createAnnotation(entityClass, OrderColumn.class);

		final StringMemberValue val = new StringMemberValue(field.getFieldInfo2().getConstPool());
		val.setValue("pk");
		jsonSerializeAnn.addMemberValue("name", val);

		return jsonSerializeAnn;
	}

	/**
	 * Necessary to prohibit infinite loops when serializating using Jackson
	 */
	protected Annotation createSerializationAnnotation(final CtClass entityClass, final CtField field,
			final String serializerClassName) throws IllegalClassTransformationException {

		final Annotation jsonSerializeAnn = createAnnotation(entityClass, JsonSerialize.class);

		final ClassMemberValue val = new ClassMemberValue(field.getFieldInfo2().getConstPool());
		val.setValue(serializerClassName);
		jsonSerializeAnn.addMemberValue("using", val);

		return jsonSerializeAnn;
	}

	protected void addMappedByAnnotationValue(final CtField field, final Annotation annotation,
			final CtClass entityClass, final Annotation relation) {
		if (relation != null) {
			final StringMemberValue mappedTo = (StringMemberValue) relation.getMemberValue(MV_MAPPED_TO);

			annotation.addMemberValue(MV_MAPPED_BY,
					createAnnotationStringValue(field.getFieldInfo2().getConstPool(), mappedTo.getValue()));
		}
	}

	protected Annotation createJpaRelationAnnotation(final CtClass clazz, final CtField field,
			final Class<? extends java.lang.annotation.Annotation> annotationType)
			throws IllegalClassTransformationException {

		final Annotation ann = createAnnotation(clazz, annotationType);
		addCascadeAnnotation(ann, field);

		return ann;
	}

	/**
	 * Creates a {@link JoinColumn} annotation annotation in case the property has a
	 * unique=true modifier.
	 */
	protected Annotation createJoinColumnAnnotation(final CtClass clazz, final CtField field)
			throws IllegalClassTransformationException {

		final Annotation ann = createAnnotation(clazz, JoinColumn.class);

		final Optional<Annotation> propAnnotation = getAnnotation(field, Property.class);

		if (propAnnotation.isPresent()) {
			final BooleanMemberValue uniqueVal = (BooleanMemberValue) propAnnotation.get().getMemberValue("unique");

			if (uniqueVal != null && uniqueVal.getValue()) {
				// unique value
				// final BooleanMemberValue unique = new
				// BooleanMemberValue(field.getFieldInfo2().getConstPool());
				// unique.setValue(true);
				//
				// ann.addMemberValue(MV_UNIQUE, unique);

				// nullable value
				final BooleanMemberValue nullable = new BooleanMemberValue(field.getFieldInfo2().getConstPool());
				nullable.setValue(false);

				ann.addMemberValue(MV_NULLABLE, nullable);
			}
		}

		// column name
		final StringMemberValue columnName = new StringMemberValue(field.getFieldInfo2().getConstPool());
		columnName.setValue(field.getName());

		ann.addMemberValue(MV_NAME, columnName);

		return ann;
	}

	protected Annotation createJoinTableAnnotation(final CtClass clazz, final CtField field,
			final Annotation propertyAnnotation, final Annotation relationAnnotation) {

		final StringMemberValue relationNameValue = (StringMemberValue) relationAnnotation
				.getMemberValue(MV_RELATION_NAME);

		// @JoinTable
		final Annotation joinTableAnn = createAnnotation(field.getFieldInfo2().getConstPool(), JoinTable.class);
		final StringMemberValue tableName = new StringMemberValue(field.getFieldInfo2().getConstPool());

		// generate relation table name
		tableName.setValue(relationNameValue.getValue());
		joinTableAnn.addMemberValue(MV_NAME, tableName);

		{// swap relationnode types according to the relation setting
			String joinColumnName = RELATION_SOURCE_COLUMN;
			String inverseJoinColumnName = RELATION_TARGET_COLUMN;

			final RelationNodeType nodeType = getRelationNodeType(relationAnnotation);

			if (RelationNodeType.TARGET.equals(nodeType)) {
				joinColumnName = RELATION_TARGET_COLUMN;
				inverseJoinColumnName = RELATION_SOURCE_COLUMN;
			}

			joinTableAnn.addMemberValue(MV_JOIN_COLUMNS, createJoinColumn(field, joinColumnName));
			joinTableAnn.addMemberValue(MV_INVERSE_JOIN_COLUMNS, createJoinColumn(field, inverseJoinColumnName));
		}

		return joinTableAnn;
	}

	protected ArrayMemberValue createJoinColumn(final CtField field, final String columnName) {
		final Annotation joinColumnAnn = createAnnotation(field.getFieldInfo2().getConstPool(), JoinColumn.class);

		final StringMemberValue column = new StringMemberValue(field.getFieldInfo2().getConstPool());
		column.setValue(MV_PK);
		joinColumnAnn.addMemberValue(MV_REFERENCED_COLUMN_NAME, column);

		final StringMemberValue name = new StringMemberValue(field.getFieldInfo2().getConstPool());
		name.setValue(columnName);
		joinColumnAnn.addMemberValue(MV_NAME, name);

		final AnnotationMemberValue val = new AnnotationMemberValue(field.getFieldInfo2().getConstPool());
		val.setValue(joinColumnAnn);

		return createAnnotationArrayValue(field.getFieldInfo2().getConstPool(), val);
	}

	protected RelationNodeType getRelationNodeType(final Annotation relationAnnotation) {
		final EnumMemberValue nodeType = (EnumMemberValue) relationAnnotation.getMemberValue(MV_NODE_TYPE);
		return RelationNodeType.valueOf(nodeType.getValue());
	}

	protected void addCascadeAnnotation(final Annotation annotation, final CtField field) {
		final EnumMemberValue val = new EnumMemberValue(field.getFieldInfo2().getConstPool());
		val.setType(CascadeType.class.getName());
		val.setValue(CascadeType.ALL.toString());

		annotation.addMemberValue(MV_CASCADE, createAnnotationArrayValue(field.getFieldInfo2().getConstPool(), val));
	}

}
