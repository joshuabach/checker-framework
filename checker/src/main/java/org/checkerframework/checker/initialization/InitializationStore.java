package org.checkerframework.checker.initialization;

import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.visualize.CFGVisualizer;
import org.checkerframework.dataflow.expression.ClassName;
import org.checkerframework.dataflow.expression.FieldAccess;
import org.checkerframework.dataflow.expression.JavaExpression;
import org.checkerframework.dataflow.expression.ThisReference;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFAbstractStore;
import org.checkerframework.framework.flow.CFAbstractValue;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.plumelib.util.CollectionsPlume;
import org.plumelib.util.ToStringComparator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.VariableElement;

/**
 * A store that extends {@code CFAbstractStore} and additionally tracks which fields of the 'self'
 * reference have been initialized.
 *
 * @see InitializationTransfer
 */
public class InitializationStore<V extends CFAbstractValue<V>, S extends InitializationStore<V, S>>
        extends CFAbstractStore<V, S> {

    /** The set of fields that are initialized. */
    protected final Set<VariableElement> initializedFields;
    /** The set of fields that have the 'invariant' annotation, and their value. */
    protected final Map<FieldAccess, V> invariantFields;

    /**
     * Creates a new InitializationStore.
     *
     * @param analysis the analysis class this store belongs to
     * @param sequentialSemantics should the analysis use sequential Java semantics?
     */
    public InitializationStore(CFAbstractAnalysis<V, S, ?> analysis, boolean sequentialSemantics) {
        super(analysis, sequentialSemantics);
        initializedFields = new HashSet<>(4);
        invariantFields = new HashMap<>(4);
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the receiver is a field, and has an invariant annotation, then it can be considered
     * initialized.
     */
    @Override
    public void insertValue(JavaExpression je, V value, boolean permitNondeterministic) {
        if (!shouldInsert(je, value, permitNondeterministic)) {
            return;
        }

        InitializationAnnotatedTypeFactory<?, ?, ?, ?> atypeFactory =
                (InitializationAnnotatedTypeFactory<?, ?, ?, ?>) analysis.getTypeFactory();

        // Remember fields that have an invariant annotation in the store.
        if (je instanceof FieldAccess) {
            FieldAccess fieldAccess = (FieldAccess) je;
            if (!fieldValues.containsKey(je)) {
                VariableElement field = fieldAccess.getField();
                AnnotatedTypeMirror type = atypeFactory.getAnnotatedType(field);
                AnnotationMirror inv = atypeFactory.getFieldInvariantAnnotation(type, field);

                if (inv != null) {
                    if (!invariantFields.containsKey(fieldAccess)) {
                        invariantFields.put(
                                fieldAccess,
                                analysis.createSingleAnnotationValue(inv, je.getType()));
                    }
                }
            }
        }

        super.insertValue(je, value, permitNondeterministic);

        for (AnnotationMirror a : value.getAnnotations()) {
            if (atypeFactory.isFieldInvariantAnnotation(a)) {
                if (je instanceof FieldAccess) {
                    FieldAccess fa = (FieldAccess) je;
                    if (fa.getReceiver() instanceof ThisReference
                            || fa.getReceiver() instanceof ClassName) {
                        addInitializedField(fa.getField());
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Additionally, the {@link InitializationStore} keeps all field values for fields that have
     * the 'invariant' annotation.
     */
    @Override
    public void updateForMethodCall(
            MethodInvocationNode n, AnnotatedTypeFactory atypeFactory, V val) {
        // Remove invariant annotated fields to avoid performance issue reported in #1438.
        for (FieldAccess invariantField : invariantFields.keySet()) {
            fieldValues.remove(invariantField);
        }

        super.updateForMethodCall(n, atypeFactory, val);

        // Add invariant annotation again.
        fieldValues.putAll(invariantFields);
    }

    /** A copy constructor. */
    public InitializationStore(S other) {
        super(other);
        initializedFields = new HashSet<>(other.initializedFields);
        invariantFields = new HashMap<>(other.invariantFields);
    }

    /**
     * Mark the field identified by the element {@code field} as initialized if it belongs to the
     * current class, or is static (in which case there is no aliasing issue and we can just add all
     * static fields).
     *
     * @param field a field that is initialized
     */
    public void addInitializedField(FieldAccess field) {
        boolean fieldOnThisReference = field.getReceiver() instanceof ThisReference;
        boolean staticField = field.isStatic();
        if (fieldOnThisReference || staticField) {
            initializedFields.add(field.getField());
        }
    }

    /**
     * Mark the field identified by the element {@code f} as initialized (the caller needs to ensure
     * that the field belongs to the current class, or is a static field).
     *
     * @param f a field that is initialized
     */
    public void addInitializedField(VariableElement f) {
        initializedFields.add(f);
    }

    /** Is the field identified by the element {@code f} initialized? */
    public boolean isFieldInitialized(Element f) {
        return initializedFields.contains(f);
    }

    @Override
    protected boolean supersetOf(CFAbstractStore<V, S> o) {
        if (!(o instanceof InitializationStore)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        S other = (S) o;

        for (Element field : other.initializedFields) {
            if (!initializedFields.contains(field)) {
                return false;
            }
        }

        for (FieldAccess invariantField : other.invariantFields.keySet()) {
            if (!invariantFields.containsKey(invariantField)) {
                return false;
            }
        }

        Map<FieldAccess, V> removedFieldValues = new HashMap<>(invariantFields.size());
        Map<FieldAccess, V> removedOtherFieldValues = new HashMap<>(other.invariantFields.size());
        try {
            // Remove invariant annotated fields to avoid performance issue reported in #1438.
            for (FieldAccess invariantField : invariantFields.keySet()) {
                V v = fieldValues.remove(invariantField);
                removedFieldValues.put(invariantField, v);
            }
            for (FieldAccess invariantField : other.invariantFields.keySet()) {
                V v = other.fieldValues.remove(invariantField);
                removedOtherFieldValues.put(invariantField, v);
            }

            return super.supersetOf(other);
        } finally {
            // Restore removed values.
            fieldValues.putAll(removedFieldValues);
            other.fieldValues.putAll(removedOtherFieldValues);
        }
    }

    @Override
    public S leastUpperBound(S other) {
        // Remove invariant annotated fields to avoid performance issue reported in #1438.
        Map<FieldAccess, V> removedFieldValues = new HashMap<>(invariantFields.size());
        Map<FieldAccess, V> removedOtherFieldValues = new HashMap<>(other.invariantFields.size());
        for (FieldAccess invariantField : invariantFields.keySet()) {
            V v = fieldValues.remove(invariantField);
            removedFieldValues.put(invariantField, v);
        }
        for (FieldAccess invariantField : other.invariantFields.keySet()) {
            V v = other.fieldValues.remove(invariantField);
            removedOtherFieldValues.put(invariantField, v);
        }

        S result = super.leastUpperBound(other);

        // Restore removed values.
        fieldValues.putAll(removedFieldValues);
        other.fieldValues.putAll(removedOtherFieldValues);

        // Set intersection for initializedFields.
        result.initializedFields.addAll(other.initializedFields);
        result.initializedFields.retainAll(initializedFields);

        // Set intersection for invariantFields.
        for (Map.Entry<FieldAccess, V> e : invariantFields.entrySet()) {
            FieldAccess key = e.getKey();
            if (other.invariantFields.containsKey(key)) {
                // TODO: Is the value other.invariantFields.get(key) the same as e.getValue()?
                // Should the two values be lubbed?
                result.invariantFields.put(key, e.getValue());
            }
        }
        // Add invariant annotation.
        result.fieldValues.putAll(result.invariantFields);

        return result;
    }

    @Override
    protected String internalVisualize(CFGVisualizer<V, S, ?> viz) {
        String superVisualize = super.internalVisualize(viz);

        String initializedVisualize =
                viz.visualizeStoreKeyVal(
                        "initialized fields", ToStringComparator.sorted(initializedFields));

        List<VariableElement> invariantVars =
                CollectionsPlume.mapList(FieldAccess::getField, invariantFields.keySet());
        String invariantVisualize =
                viz.visualizeStoreKeyVal(
                        "invariant fields", ToStringComparator.sorted(invariantVars));

        if (superVisualize.isEmpty()) {
            return String.join(viz.getSeparator(), initializedVisualize, invariantVisualize);
        } else {
            return String.join(
                    viz.getSeparator(), superVisualize, initializedVisualize, invariantVisualize);
        }
    }

    /**
     * Returns the analysis associated with this store.
     *
     * @return the analysis associated with this store
     */
    public CFAbstractAnalysis<V, S, ?> getAnalysis() {
        return analysis;
    }
}
