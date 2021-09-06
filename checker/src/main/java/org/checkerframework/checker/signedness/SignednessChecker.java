package org.checkerframework.checker.signedness;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueChecker;

import java.util.LinkedHashSet;

/**
 * A type-checker that prevents mixing of unsigned and signed values, and prevents meaningless
 * operations on unsigned values.
 *
 * @checker_framework.manual #signedness-checker Signedness Checker
 */
public class SignednessChecker extends BaseTypeChecker {

    @Override
    protected LinkedHashSet<BaseTypeChecker> getImmediateSubcheckers() {
        LinkedHashSet<BaseTypeChecker> checkers = super.getImmediateSubcheckers();
        checkers.add(new ValueChecker());
        return checkers;
    }
}
