package org.checkerframework.checker.index.searchindex;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.framework.qual.RelevantJavaTypes;
import org.checkerframework.framework.source.SuppressWarningsPrefix;

import java.util.LinkedHashSet;

/**
 * An internal checker that assists the Index Checker in typing the results of calls to the JDK's
 * {@link java.util.Arrays#binarySearch(Object[],Object) Arrays.binarySearch} routine.
 *
 * @checker_framework.manual #index-checker Index Checker
 */
@SuppressWarningsPrefix({"index", "searchindex"})
@RelevantJavaTypes({
    Byte.class,
    Short.class,
    Integer.class,
    Long.class,
    Character.class,
    byte.class,
    short.class,
    int.class,
    long.class,
    char.class,
})
public class SearchIndexChecker extends BaseTypeChecker {

    @Override
    protected LinkedHashSet<BaseTypeChecker> getImmediateSubcheckers() {
        LinkedHashSet<BaseTypeChecker> checkers = super.getImmediateSubcheckers();
        checkers.add(new ValueChecker());
        return checkers;
    }
}
