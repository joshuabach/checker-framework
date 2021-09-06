package org.checkerframework.framework.testchecker.compound;

import org.checkerframework.common.aliasing.AliasingChecker;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.common.value.ValueChecker;

import java.util.LinkedHashSet;

public class AnotherCompoundChecker extends BaseTypeChecker {
    @Override
    protected LinkedHashSet<BaseTypeChecker> getImmediateSubcheckers() {
        // Make sure that options can be accessed by sub-checkers to determine
        // which subcheckers to run.
        @SuppressWarnings("unused")
        String option = super.getOption("nomsgtext");
        LinkedHashSet<BaseTypeChecker> subcheckers = new LinkedHashSet<>();
        subcheckers.addAll(super.getImmediateSubcheckers());
        subcheckers.add(new AliasingChecker());
        subcheckers.add(new ValueChecker());
        return subcheckers;
    }

    @Override
    protected BaseTypeVisitor<?> createSourceVisitor() {
        return new BaseTypeVisitor<AnotherCompoundCheckerAnnotatedTypeFactory>(this) {
            @Override
            protected AnotherCompoundCheckerAnnotatedTypeFactory createTypeFactory() {
                return new AnotherCompoundCheckerAnnotatedTypeFactory(checker);
            }
        };
    }
}
