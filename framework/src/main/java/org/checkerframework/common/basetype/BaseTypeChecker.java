package org.checkerframework.common.basetype;

import com.google.common.collect.ImmutableSet;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import org.checkerframework.checker.interning.qual.FindDistinct;
import org.checkerframework.checker.interning.qual.InternedDistinct;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.signature.qual.ClassGetName;
import org.checkerframework.common.reflection.MethodValChecker;
import org.checkerframework.dataflow.cfg.visualize.CFGVisualizer;
import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.TypeHierarchy;
import org.checkerframework.framework.util.TreePathCacher;
import org.checkerframework.javacutil.AbstractTypeProcessor;
import org.checkerframework.javacutil.AnnotationProvider;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.InternalUtils;
import org.checkerframework.javacutil.TypeSystemError;
import org.checkerframework.javacutil.UserError;
import org.plumelib.util.CollectionsPlume;
import org.plumelib.util.StringsPlume;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * An abstract {@link SourceChecker} that provides a simple {@link
 * org.checkerframework.framework.source.SourceVisitor} implementation that type-checks assignments,
 * pseudo-assignments such as parameter passing and method invocation, and method overriding.
 *
 * <p>Most type-checker annotation processor should extend this class, instead of {@link
 * SourceChecker}. Checkers that require annotated types but not subtype checking (e.g. for testing
 * purposes) should extend {@link SourceChecker}. Non-type checkers (e.g. checkers to enforce coding
 * styles) can extend {@link SourceChecker} or {@link AbstractTypeProcessor}; the Checker Framework
 * is not designed for such checkers.
 *
 * <p>It is a convention that, for a type system Foo, the checker, the visitor, and the annotated
 * type factory are named as <i>FooChecker</i>, <i>FooVisitor</i>, and
 * <i>FooAnnotatedTypeFactory</i>. Some factory methods use this convention to construct the
 * appropriate classes reflectively.
 *
 * <p>{@code BaseTypeChecker} encapsulates a group for factories for various representations/classes
 * related the type system, mainly:
 *
 * <ul>
 *   <li>{@link QualifierHierarchy}: to represent the supported qualifiers in addition to their
 *       hierarchy, mainly, subtyping rules
 *   <li>{@link TypeHierarchy}: to check subtyping rules between <b>annotated types</b> rather than
 *       qualifiers
 *   <li>{@link AnnotatedTypeFactory}: to construct qualified types enriched with default qualifiers
 *       according to the type system rules
 *   <li>{@link BaseTypeVisitor}: to visit the compiled Java files and check for violations of the
 *       type system rules
 * </ul>
 *
 * <p>Subclasses must specify the set of type qualifiers they support. See {@link
 * AnnotatedTypeFactory#createSupportedTypeQualifiers()}.
 *
 * <p>If the specified type qualifiers are meta-annotated with {@link SubtypeOf}, this
 * implementation will automatically construct the type qualifier hierarchy. Otherwise, or if this
 * behavior must be overridden, the subclass may override the {@link
 * BaseAnnotatedTypeFactory#createQualifierHierarchy()} method.
 *
 * @checker_framework.manual #creating-compiler-interface The checker class
 */
public abstract class BaseTypeChecker extends SourceChecker {

    /*
     * For most checkers, their instanced need not be distinguished since the checker only runs
     * once, and there are never two checkers of the same class.
     *
     * If this is not the case for your checker class, you should override hashCode and equals
     * to uniquely identify each checker.
     */

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj.getClass() == getClass();
    }

    @Override
    public void initChecker() {
        // initialize all checkers and share options as necessary
        for (BaseTypeChecker checker : getSubcheckers()) {
            // We need to add all options that are activated for the set of subcheckers to
            // the individual checkers.
            checker.addOptions(super.getOptions());
            // Each checker should "support" all possible lint options - otherwise
            // subchecker A would complain about a lint option for subchecker B.
            checker.setSupportedLintOptions(this.getSupportedLintOptions());

            // initChecker validates the passed options, so call it after setting supported options
            // and lints.
            checker.initChecker();
        }

        if (!getSubcheckers().isEmpty()) {
            messageStore = new TreeSet<>(this::compareCheckerMessages);
        }

        super.initChecker();
    }

    /**
     * The full list of subcheckers that need to be run prior to this one, in the order they need to
     * be run in. This list will only be non-empty for the one checker that runs all other
     * subcheckers. Do not read this field directly. Instead, retrieve it via {@link
     * #getSubcheckers}.
     *
     * <p>If the list still null when {@link #getSubcheckers} is called, then getSubcheckers() will
     * call {@link #instantiateSubcheckers}. However, if the current object was itself instantiated
     * by a prior call to instantiateSubcheckers, this field will have been initialized to an empty
     * list before getSubcheckers() is called, thereby ensuring that this list is non-empty only for
     * one checker.
     */
    private @MonotonicNonNull List<BaseTypeChecker> subcheckers = null;

    /**
     * The list of subcheckers that are direct dependencies of this checker. This list will be
     * non-empty for any checker that has at least one subchecker.
     *
     * <p>Does not need to be initialized to null or an empty list because it is always initialized
     * via calls to instantiateSubcheckers.
     */
    // Set to non-null when subcheckers is.
    private @MonotonicNonNull List<BaseTypeChecker> immediateSubcheckers = null;

    /** Supported options for this checker. */
    private @MonotonicNonNull Set<String> supportedOptions = null;

    /** Options passed to this checker. */
    private @MonotonicNonNull Map<String, String> options = null;

    /**
     * TreePathCacher to share between instances. Initialized either in getTreePathCacher (which is
     * also called from instantiateSubcheckers).
     */
    private TreePathCacher treePathCacher = null;

    /**
     * The list of suppress warnings prefixes supported by this checker or any of its subcheckers
     * (including indirect subcheckers). Do not access this field directly; instead, use {@link
     * #getSuppressWarningsPrefixesOfSubcheckers}.
     */
    private @MonotonicNonNull Collection<String> suppressWarningsPrefixesOfSubcheckers = null;

    @Override
    protected void setRoot(CompilationUnitTree newRoot) {
        super.setRoot(newRoot);
        if (parentChecker == null) {
            // Only clear the path cache if this is the main checker.
            treePathCacher.clear();
        }
    }

    /**
     * Returns the set of immediate subcheckers on which this checker depends. Returns an empty set
     * if this checker does not depend on any others.
     *
     * <p>Subclasses should override this method to specify subcheckers. If they do so, they should
     * call the super implementation of this method and add dependencies to the returned set so that
     * checkers required for reflection resolution are included if reflection resolution is
     * requested.
     *
     * <p>Each subchecker of this checker may also depend on other checkers. If this checker and one
     * of its subcheckers both depend on a third checker, that checker will only be instantiated
     * once.
     *
     * <p>Though each checker is run on a whole compilation unit before the next checker is run,
     * error and warning messages are collected and sorted based on the location in the source file
     * before being printed. (See {@link #printOrStoreMessage(Diagnostic.Kind, String, Tree,
     * CompilationUnitTree)}.)
     *
     * <p>WARNING: Circular dependencies are not supported nor do checkers verify that their
     * dependencies are not circular. Make sure no circular dependencies are created when overriding
     * this method. (In other words, if checker A depends on checker B, checker B cannot depend on
     * checker A.)
     *
     * <p>This method is protected so it can be overridden, but it should only be called internally
     * by the BaseTypeChecker.
     *
     * <p>The BaseTypeChecker will not modify the list returned by this method, but other clients do
     * modify the list.
     *
     * @return the subchecker classes on which this checker depends
     */
    protected LinkedHashSet<BaseTypeChecker> getImmediateSubcheckers() {
        if (shouldResolveReflection()) {
            return new LinkedHashSet<>(Collections.singleton(new MethodValChecker()));
        }
        // The returned set will be modified by callees.
        return new LinkedHashSet<>();
    }

    /**
     * Returns whether or not reflection should be resolved.
     *
     * @return true if reflection should be resolved
     */
    public boolean shouldResolveReflection() {
        return hasOptionNoSubcheckers("resolveReflection");
    }

    /**
     * Returns the appropriate visitor that type-checks the compilation unit according to the type
     * system rules.
     *
     * <p>This implementation uses the checker naming convention to create the appropriate visitor.
     * If no visitor is found, it returns an instance of {@link BaseTypeVisitor}. It reflectively
     * invokes the constructor that accepts this checker and the compilation unit tree (in that
     * order) as arguments.
     *
     * <p>Subclasses have to override this method to create the appropriate visitor if they do not
     * follow the checker naming convention.
     *
     * @return the type-checking visitor
     */
    @Override
    protected BaseTypeVisitor<?> createSourceVisitor() {
        // Try to reflectively load the visitor.
        Class<?> checkerClass = this.getClass();

        while (checkerClass != BaseTypeChecker.class) {
            BaseTypeVisitor<?> result =
                    invokeConstructorFor(
                            BaseTypeChecker.getRelatedClassName(checkerClass, "Visitor"),
                            new Class<?>[] {BaseTypeChecker.class},
                            new Object[] {this});
            if (result != null) {
                return result;
            }
            checkerClass = checkerClass.getSuperclass();
        }

        // If a visitor couldn't be loaded reflectively, return the default.
        return new BaseTypeVisitor<BaseAnnotatedTypeFactory>(this);
    }

    /**
     * A public variant of {@link #createSourceVisitor}. Only use this if you know what you are
     * doing.
     *
     * @return the type-checking visitor
     */
    public BaseTypeVisitor<?> createSourceVisitorPublic() {
        return createSourceVisitor();
    }

    /**
     * Returns the name of a class related to a given one, by replacing "Checker" or "Subchecker" by
     * {@code replacement}.
     *
     * @param checkerClass the checker class
     * @param replacement the string to replace "Checker" or "Subchecker" by
     * @return the name of the related class
     */
    @SuppressWarnings("signature") // string manipulation of @ClassGetName string
    public static @ClassGetName String getRelatedClassName(
            Class<?> checkerClass, String replacement) {
        return checkerClass
                .getName()
                .replace("Checker", replacement)
                .replace("Subchecker", replacement);
    }

    // **********************************************************************
    // Misc. methods
    // **********************************************************************

    /** Specify supported lint options for all type-checkers. */
    @Override
    public Set<String> getSupportedLintOptions() {
        Set<String> lintSet = new HashSet<>(super.getSupportedLintOptions());
        lintSet.add("cast");
        lintSet.add("cast:redundant");
        lintSet.add("cast:unsafe");

        for (BaseTypeChecker checker : getSubcheckers()) {
            lintSet.addAll(checker.getSupportedLintOptions());
        }

        return Collections.unmodifiableSet(lintSet);
    }

    /**
     * Invokes the constructor belonging to the class named by {@code name} having the given
     * parameter types on the given arguments. Returns {@code null} if the class cannot be found.
     * Otherwise, throws an exception if there is trouble with the constructor invocation.
     *
     * @param <T> the type to which the constructor belongs
     * @param name the name of the class to which the constructor belongs
     * @param paramTypes the types of the constructor's parameters
     * @param args the arguments on which to invoke the constructor
     * @return the result of the constructor invocation on {@code args}, or null if the class does
     *     not exist
     */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"}) // Intentional abuse
    public static <T> T invokeConstructorFor(
            @ClassGetName String name, Class<?>[] paramTypes, Object[] args) {

        // Load the class.
        Class<T> cls = null;
        try {
            cls = (Class<T>) Class.forName(name);
        } catch (Exception e) {
            // no class is found, simply return null
            return null;
        }

        assert cls != null : "reflectively loading " + name + " failed";

        // Invoke the constructor.
        try {
            Constructor<T> ctor = cls.getConstructor(paramTypes);
            return ctor.newInstance(args);
        } catch (Throwable t) {
            if (t instanceof InvocationTargetException) {
                Throwable err = t.getCause();
                if (err instanceof UserError || err instanceof TypeSystemError) {
                    // Don't add more information about the constructor invocation.
                    throw (RuntimeException) err;
                }
            }
            Throwable cause;
            String causeMessage;
            if (t instanceof InvocationTargetException) {
                cause = t.getCause();
                if (cause == null || cause.getMessage() == null) {
                    causeMessage = t.getMessage();
                } else if (t.getMessage() == null) {
                    causeMessage = cause.getMessage();
                } else {
                    causeMessage = t.getMessage() + ": " + cause.getMessage();
                }
            } else {
                cause = t;
                causeMessage = (cause == null) ? "null" : cause.getMessage();
            }
            throw new BugInCF(
                    cause,
                    "Error when invoking constructor %s(%s) on args %s; cause: %s",
                    name,
                    StringsPlume.join(", ", paramTypes),
                    Arrays.toString(args),
                    causeMessage);
        }
    }

    @Override
    public BaseTypeVisitor<?> getVisitor() {
        return (BaseTypeVisitor<?>) super.getVisitor();
    }

    /**
     * Return the type factory associated with this checker.
     *
     * @return the type factory associated with this checker
     */
    public GenericAnnotatedTypeFactory<?, ?, ?, ?> getTypeFactory() {
        BaseTypeVisitor<?> visitor = getVisitor();
        // Avoid NPE if this method is called during initialization.
        if (visitor == null) {
            return null;
        }
        return visitor.getTypeFactory();
    }

    @Override
    public AnnotationProvider getAnnotationProvider() {
        return getTypeFactory();
    }

    /**
     * Returns the first subchecker of the given class, or null if none was found. The caller must
     * know the exact checker class to request.
     *
     * @param checkerClass the class of the subchecker
     * @return the first subchecker of the given class or null if not found
     * @see #getSubcheckers(Class)
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseTypeChecker> T getSubchecker(Class<T> checkerClass) {
        for (BaseTypeChecker checker : immediateSubcheckers) {
            if (checker.getClass() == checkerClass) {
                return (T) checker;
            }
        }

        return null;
    }

    /**
     * Returns all subcheckers of the given class, or an empty list if none was found. The caller
     * must know the exact checker class to request.
     *
     * @param <T> the class of the subchecker
     * @param checkerClass the class of the subchecker
     * @return all subcheckers of the given class or an empty list if not found
     * @see #getSubchecker(Class)
     */
    @SuppressWarnings("unchecked")
    public <T extends BaseTypeChecker> List<T> getSubcheckers(Class<T> checkerClass) {
        ArrayList<T> result = new ArrayList<>();

        for (BaseTypeChecker checker : immediateSubcheckers) {
            if (checker.getClass() == checkerClass) {
                result.add((T) checker);
            }
        }

        return result;
    }

    /**
     * Returns the type factory used by a subchecker. Returns null if no matching subchecker was
     * found or if the type factory is null. The caller must know the exact checker class to
     * request.
     *
     * <p>Because the visitor state is copied, call this method each time a subfactory is needed
     * rather than store the returned subfactory in a field.
     *
     * @param subCheckerClass the class of the subchecker
     * @param <T> the type of {@code subCheckerClass}'s {@link AnnotatedTypeFactory}
     * @return the type factory of the requested subchecker or null if not found
     * @see #getTypeFactoriesOfSubcheckers(Class)
     */
    @SuppressWarnings("TypeParameterUnusedInFormals") // Intentional abuse
    public <T extends GenericAnnotatedTypeFactory<?, ?, ?, ?>>
            @Nullable T getTypeFactoryOfSubchecker(Class<? extends BaseTypeChecker> subCheckerClass) {
        return getTypeFactory().getTypeFactoryOfSubchecker(subCheckerClass);
    }

    /**
     * Returns all type factories used by a subchecker of the given class. Returns an empty list if
     * no matching subchecker was found. The caller must know the exact checker class to request.
     *
     * @param <T> the class of the type factories of the subcheckers
     * @param <U> the class of the subcheckers
     * @param checkerClass the class of the subcheckers
     * @return the type factories of the requested subchecker, or an empty list if not found.
     * @see #getTypeFactoryOfSubchecker(Class)
     */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"}) // Intentional abuse
    public <T extends GenericAnnotatedTypeFactory<?, ?, ?, ?>, U extends BaseTypeChecker>
            List<T> getTypeFactoriesOfSubcheckers(Class<U> checkerClass) {
        List<U> checkers = getSubcheckers(checkerClass);
        List<T> result = new ArrayList<>();

        for (BaseTypeChecker checker : checkers) {
            result.add((T) checker.getTypeFactory());
        }

        return result;
    }

    /**
     * Performs a depth first search for all checkers this checker depends on. The depth first
     * search ensures that the collection has the correct order the checkers need to be run in.
     *
     * <p>Modifies the alreadyInitializedSubcheckerMap map by adding all recursively newly
     * instantiated subcheckers. alreadyInitializedSubcheckerMap is an equivalence map, mapping a
     * checker to another, already initialized, checker with the same hash code. A LinkedHashMap is
     * used because, unlike HashMap, it preserves the order in which entries were inserted.
     *
     * @param alreadyInitializedSubcheckerMap an equivalence map, mapping a checker to another,
     *     already initialized, checker with the same hash code.
     * @return the unmodifiable list of immediate subcheckers of this checker.
     */
    private List<BaseTypeChecker> instantiateSubcheckers(
            LinkedHashMap<BaseTypeChecker, BaseTypeChecker> alreadyInitializedSubcheckerMap) {
        LinkedHashSet<BaseTypeChecker> immediateSubcheckers = getImmediateSubcheckers();
        if (immediateSubcheckers.isEmpty()) {
            return Collections.emptyList();
        }

        List<BaseTypeChecker> result = new ArrayList<>(immediateSubcheckers.size());

        for (BaseTypeChecker subchecker : immediateSubcheckers) {
            BaseTypeChecker initializedSubchecker = alreadyInitializedSubcheckerMap.get(subchecker);
            if (initializedSubchecker != null) {
                // Add the already initialized subchecker to the list of immediate subcheckers so
                // that this checker can refer to it.
                result.add(initializedSubchecker);
                continue;
            }

            subchecker.setProcessingEnvironment(this.processingEnv);
            subchecker.treePathCacher = this.getTreePathCacher();
            // Prevent the new checker from storing non-immediate subcheckers
            subchecker.subcheckers = Collections.emptyList();
            result.add(subchecker);
            subchecker.immediateSubcheckers =
                    subchecker.instantiateSubcheckers(alreadyInitializedSubcheckerMap);
            subchecker.setParentChecker(this);
            alreadyInitializedSubcheckerMap.put(subchecker, subchecker);
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Get the list of all subcheckers (if any). via the instantiateSubcheckers method. This list is
     * only non-empty for the one checker that runs all other subcheckers. These are recursively
     * instantiated via instantiateSubcheckers the first time the method is called if subcheckers is
     * null. Assumes all checkers run on the same thread.
     *
     * @return the list of all subcheckers (if any)
     */
    public List<BaseTypeChecker> getSubcheckers() {
        if (subcheckers == null) {
            // Instantiate the checkers this one depends on, if any.
            LinkedHashMap<BaseTypeChecker, BaseTypeChecker> checkerMap = new LinkedHashMap<>(1);

            immediateSubcheckers = instantiateSubcheckers(checkerMap);

            subcheckers = Collections.unmodifiableList(new ArrayList<>(checkerMap.values()));
        }

        return subcheckers;
    }

    /** Get the shared TreePathCacher instance. */
    public TreePathCacher getTreePathCacher() {
        if (treePathCacher == null) {
            // In case it wasn't already set in instantiateSubcheckers.
            treePathCacher = new TreePathCacher();
        }
        return treePathCacher;
    }

    @Override
    protected void reportJavacError(TreePath p) {
        if (parentChecker == null) {
            // Only the parent checker should report the "type.checking.not.run" error.
            super.reportJavacError(p);
        }
    }

    // AbstractTypeProcessor delegation
    @Override
    public void typeProcess(TypeElement element, TreePath tree) {
        if (!getSubcheckers().isEmpty()) {
            // TODO: I expected this to only be necessary if (parentChecker == null).
            // However, the NestedAggregateChecker fails otherwise.
            messageStore.clear();
        }

        // Errors (or other messages) issued via
        //   SourceChecker#message(Diagnostic.Kind, Object, String, Object...)
        // are stored in messageStore until all checkers have processed this compilation unit.
        // All other messages are printed immediately.  This includes errors issued because the
        // checker threw an exception.

        // In order to run the next checker on this compilation unit even if the previous issued
        // errors, the next checker's errsOnLastExit needs to include all errors issued by previous
        // checkers.

        Context context = ((JavacProcessingEnvironment) processingEnv).getContext();
        Log log = Log.instance(context);

        int nerrorsOfAllPreviousCheckers = this.errsOnLastExit;
        for (BaseTypeChecker subchecker : getSubcheckers()) {
            subchecker.errsOnLastExit = nerrorsOfAllPreviousCheckers;
            subchecker.messageStore = messageStore;
            int errorsBeforeTypeChecking = log.nerrors;

            subchecker.typeProcess(element, tree);

            int errorsAfterTypeChecking = log.nerrors;
            nerrorsOfAllPreviousCheckers += errorsAfterTypeChecking - errorsBeforeTypeChecking;
        }

        this.errsOnLastExit = nerrorsOfAllPreviousCheckers;
        super.typeProcess(element, tree);

        if (!getSubcheckers().isEmpty()) {
            printStoredMessages(tree.getCompilationUnit());
            // Update errsOnLastExit to reflect the errors issued.
            this.errsOnLastExit = log.nerrors;
        }
    }

    /**
     * Like {@link SourceChecker#getSuppressWarningsPrefixes()}, but includes all prefixes supported
     * by this checker or any of its subcheckers. Does not guarantee that the result is in any
     * particular order. The result is immutable.
     *
     * @return the suppress warnings prefixes supported by this checker or any of its subcheckers
     */
    public Collection<String> getSuppressWarningsPrefixesOfSubcheckers() {
        if (this.suppressWarningsPrefixesOfSubcheckers == null) {
            Collection<String> prefixes = getSuppressWarningsPrefixes();
            for (BaseTypeChecker subchecker : getSubcheckers()) {
                prefixes.addAll(subchecker.getSuppressWarningsPrefixes());
            }
            this.suppressWarningsPrefixesOfSubcheckers = ImmutableSet.copyOf(prefixes);
        }
        return this.suppressWarningsPrefixesOfSubcheckers;
    }

    /** A cache for {@link #getUltimateParentChecker}. */
    private @MonotonicNonNull BaseTypeChecker ultimateParentChecker;

    /**
     * Finds the ultimate parent checker of this checker. The ultimate parent checker is the checker
     * that the user actually requested, i.e. the one with no parent. The ultimate parent might be
     * this checker itself.
     *
     * @return the first checker in the parent checker chain with no parent checker of its own, i.e.
     *     the ultimate parent checker
     */
    public BaseTypeChecker getUltimateParentChecker() {
        if (ultimateParentChecker == null) {
            ultimateParentChecker = this;
            while (ultimateParentChecker.getParentChecker() instanceof BaseTypeChecker) {
                ultimateParentChecker = (BaseTypeChecker) ultimateParentChecker.getParentChecker();
            }
        }

        return ultimateParentChecker;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation collects needed warning suppressions for all subcheckers.
     */
    @Override
    protected void warnUnneededSuppressions() {
        if (parentChecker != null) {
            return;
        }

        if (!hasOption("warnUnneededSuppressions")) {
            return;
        }
        Set<Element> elementsWithSuppressedWarnings =
                new HashSet<>(this.elementsWithSuppressedWarnings);
        this.elementsWithSuppressedWarnings.clear();
        Set<String> prefixes = new HashSet<>(getSuppressWarningsPrefixes());
        Set<String> errorKeys = new HashSet<>(messagesProperties.stringPropertyNames());
        for (BaseTypeChecker subChecker : subcheckers) {
            elementsWithSuppressedWarnings.addAll(subChecker.elementsWithSuppressedWarnings);
            subChecker.elementsWithSuppressedWarnings.clear();
            prefixes.addAll(subChecker.getSuppressWarningsPrefixes());
            errorKeys.addAll(subChecker.messagesProperties.stringPropertyNames());
            subChecker.getVisitor().treesWithSuppressWarnings.clear();
        }
        warnUnneededSuppressions(elementsWithSuppressedWarnings, prefixes, errorKeys);

        getVisitor().treesWithSuppressWarnings.clear();
    }

    /**
     * Stores all messages issued by this checker and its subcheckers for the current compilation
     * unit. The messages are printed after all checkers have processed the current compilation
     * unit. The purpose is to sort messages, grouping together all messages about a particular line
     * of code.
     *
     * <p>If this checker has no subcheckers and is not a subchecker for any other checker, then
     * messageStore is null and messages will be printed as they are issued by this checker.
     */
    private TreeSet<CheckerMessage> messageStore = null;

    /**
     * If this is a compound checker or a subchecker of a compound checker, then the message is
     * stored until all messages from all checkers for the compilation unit are issued.
     *
     * <p>Otherwise, it prints the message.
     */
    @Override
    protected void printOrStoreMessage(
            Diagnostic.Kind kind, String message, Tree source, CompilationUnitTree root) {
        assert this.currentRoot == root;
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (messageStore == null) {
            super.printOrStoreMessage(kind, message, source, root, trace);
        } else {
            CheckerMessage checkerMessage = new CheckerMessage(kind, message, source, this, trace);
            messageStore.add(checkerMessage);
        }
    }

    /**
     * Prints error messages for this checker and all subcheckers such that the errors are ordered
     * by line and column number and then by checker. (See {@link #compareCheckerMessages} for more
     * precise order.)
     *
     * @param unit current compilation unit
     */
    private void printStoredMessages(CompilationUnitTree unit) {
        for (CheckerMessage msg : messageStore) {
            super.printOrStoreMessage(msg.kind, msg.message, msg.source, unit, msg.trace);
        }
    }

    /** Represents a message (e.g., an error message) issued by a checker. */
    private static class CheckerMessage {
        /** The severity of the message. */
        final Diagnostic.Kind kind;
        /** The message itself. */
        final String message;
        /** The source code that the message is about. */
        final @InternedDistinct Tree source;
        /** Stores the stack trace when the message is created. */
        final StackTraceElement[] trace;

        /**
         * The checker that issued this message. The compound checker that depends on this checker
         * uses this to sort the messages.
         */
        final @InternedDistinct BaseTypeChecker checker;

        /**
         * Create a new CheckerMessage.
         *
         * @param kind kind of diagnostic, for example, error or warning
         * @param message error message that needs to be printed
         * @param source tree element causing the error
         * @param checker the type-checker in use
         * @param trace the stack trace when the message is created
         */
        private CheckerMessage(
                Diagnostic.Kind kind,
                String message,
                @FindDistinct Tree source,
                @FindDistinct BaseTypeChecker checker,
                StackTraceElement[] trace) {
            this.kind = kind;
            this.message = message;
            this.source = source;
            this.checker = checker;
            this.trace = trace;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            CheckerMessage that = (CheckerMessage) o;
            return this.kind == that.kind
                    && this.message.equals(that.message)
                    && this.source == that.source
                    && this.checker == that.checker;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, message, source, checker);
        }

        @Override
        public String toString() {
            return "CheckerMessage{"
                    + "kind="
                    + kind
                    + ", checker="
                    + checker.getClass().getSimpleName()
                    + ", message='"
                    + message
                    + '\''
                    + ", source="
                    + source
                    + '}';
        }
    }

    /**
     * Compares two {@link CheckerMessage}s. Compares first by position at which the error will be
     * printed, then by kind of message, then by the message string, and finally by the order in
     * which the checkers run.
     *
     * @param o1 the first CheckerMessage
     * @param o2 the second CheckerMessage
     * @return a negative integer, zero, or a positive integer if the first CheckerMessage is less
     *     than, equal to, or greater than the second
     */
    private int compareCheckerMessages(CheckerMessage o1, CheckerMessage o2) {
        int byPos = InternalUtils.compareDiagnosticPosition(o1.source, o2.source);
        if (byPos != 0) {
            return byPos;
        }

        int kind = o1.kind.compareTo(o2.kind);
        if (kind != 0) {
            return kind;
        }

        int msgcmp = o1.message.compareTo(o2.message);
        if (msgcmp == 0) {
            // If the two messages are identical so far, it doesn't matter
            // from which checker they came.
            return 0;
        }

        // Sort by order in which the checkers are run. (All the subcheckers,
        // followed by the checker.)
        List<BaseTypeChecker> subcheckers = BaseTypeChecker.this.getSubcheckers();
        int o1Index = subcheckers.indexOf(o1.checker);
        int o2Index = subcheckers.indexOf(o2.checker);
        if (o1Index == -1) {
            o1Index = subcheckers.size();
        }
        if (o2Index == -1) {
            o2Index = subcheckers.size();
        }
        int checkercmp = Integer.compare(o1Index, o2Index);
        if (checkercmp == 0) {
            // If the two messages are from the same checker, sort by message.
            return msgcmp;
        } else {
            return checkercmp;
        }
    }

    @Override
    public void typeProcessingOver() {
        for (BaseTypeChecker checker : getSubcheckers()) {
            checker.typeProcessingOver();
        }

        super.typeProcessingOver();
    }

    @Override
    public Set<String> getSupportedOptions() {
        if (supportedOptions == null) {
            Set<String> options = new HashSet<>();
            options.addAll(super.getSupportedOptions());

            for (BaseTypeChecker checker : getSubcheckers()) {
                options.addAll(checker.getSupportedOptions());
            }

            options.addAll(
                    expandCFOptions(
                            Arrays.asList(this.getClass()), options.toArray(new String[0])));

            supportedOptions = Collections.unmodifiableSet(options);
        }
        return supportedOptions;
    }

    @Override
    public Map<String, String> getOptions() {
        if (this.options == null) {
            Map<String, String> options = new HashMap<>(super.getOptions());

            for (BaseTypeChecker checker : getSubcheckers()) {
                options.putAll(checker.getOptions());
            }
            this.options = Collections.unmodifiableMap(options);
        }

        return this.options;
    }

    /**
     * Like {@link #getOptions}, but only includes options provided to this checker. Does not
     * include those passed to subcheckers.
     *
     * @return the the active options for this checker, not including those passed to subcheckers
     */
    public Map<String, String> getOptionsNoSubcheckers() {
        return super.getOptions();
    }

    /**
     * Like {@link #hasOption}, but checks whether the given option is provided to this checker.
     * Does not consider those passed to subcheckers.
     *
     * @param name the name of the option to check
     * @return true if the option name was provided to this checker, false otherwise
     */
    public final boolean hasOptionNoSubcheckers(String name) {
        return getOptionsNoSubcheckers().containsKey(name);
    }

    /**
     * Return a list of additional stub files to be treated as if they had been written in a
     * {@code @StubFiles} annotation.
     *
     * @return stub files to be treated as if they had been written in a {@code @StubFiles}
     *     annotation
     */
    public List<String> getExtraStubFiles() {
        return new ArrayList<>();
    }

    @Override
    protected Object processArg(Object arg) {
        if (arg instanceof Collection) {
            Collection<?> carg = (Collection<?>) arg;
            return CollectionsPlume.mapList(this::processArg, carg);
        } else if (arg instanceof AnnotationMirror && getTypeFactory() != null) {
            return getTypeFactory()
                    .getAnnotationFormatter()
                    .formatAnnotationMirror((AnnotationMirror) arg);
        } else {
            return super.processArg(arg);
        }
    }

    @Override
    protected boolean shouldAddShutdownHook() {
        if (super.shouldAddShutdownHook() || getTypeFactory().getCFGVisualizer() != null) {
            return true;
        }
        for (BaseTypeChecker checker : getSubcheckers()) {
            if (checker.getTypeFactory().getCFGVisualizer() != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void shutdownHook() {
        super.shutdownHook();

        CFGVisualizer<?, ?, ?> viz = getTypeFactory().getCFGVisualizer();
        if (viz != null) {
            viz.shutdown();
        }

        for (BaseTypeChecker checker : getSubcheckers()) {
            viz = checker.getTypeFactory().getCFGVisualizer();
            if (viz != null) {
                viz.shutdown();
            }
        }
    }
}
