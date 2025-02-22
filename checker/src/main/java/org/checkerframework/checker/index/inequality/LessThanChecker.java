package org.checkerframework.checker.index.inequality;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.framework.qual.RelevantJavaTypes;
import org.checkerframework.framework.source.SuppressWarningsPrefix;

import java.util.LinkedHashSet;

/**
 * An internal checker that estimates which expression's values are less than other expressions'
 * values.
 *
 * @checker_framework.manual #index-checker Index Checker
 */
@SuppressWarningsPrefix({"index", "lessthan"})
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
public class LessThanChecker extends BaseTypeChecker {
    @Override
    protected LinkedHashSet<BaseTypeChecker> getImmediateSubcheckers() {
        LinkedHashSet<BaseTypeChecker> checkers = super.getImmediateSubcheckers();
        checkers.add(new ValueChecker());
        return checkers;
    }
}
