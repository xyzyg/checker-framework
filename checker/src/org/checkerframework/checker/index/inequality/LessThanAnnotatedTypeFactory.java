package org.checkerframework.checker.index.inequality;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.checker.index.IndexUtil;
import org.checkerframework.checker.index.OffsetDependentTypesHelper;
import org.checkerframework.checker.index.qual.LessThan;
import org.checkerframework.checker.index.qual.LessThanBottom;
import org.checkerframework.checker.index.qual.LessThanUnknown;
import org.checkerframework.checker.index.upperbound.OffsetEquation;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.value.ValueAnnotatedTypeFactory;
import org.checkerframework.common.value.ValueChecker;
import org.checkerframework.common.value.qual.IntRange;
import org.checkerframework.common.value.qual.IntVal;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.util.FlowExpressionParseUtil.FlowExpressionParseException;
import org.checkerframework.framework.util.GraphQualifierHierarchy;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy.MultiGraphFactory;
import org.checkerframework.framework.util.dependenttypes.DependentTypesHelper;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;

public class LessThanAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {
    private final AnnotationMirror BOTTOM =
            AnnotationBuilder.fromClass(elements, LessThanBottom.class);
    public final AnnotationMirror UNKNOWN =
            AnnotationBuilder.fromClass(elements, LessThanUnknown.class);

    public LessThanAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        postInit();
    }
    /** Returns the Value Checker's annotated type factory. */
    public ValueAnnotatedTypeFactory getValueAnnotatedTypeFactory() {
        return getTypeFactoryOfSubchecker(ValueChecker.class);
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new LinkedHashSet<>(
                Arrays.asList(LessThan.class, LessThanUnknown.class, LessThanBottom.class));
    }

    @Override
    protected DependentTypesHelper createDependentTypesHelper() {
        // Allows + or - in a @LessThan.
        return new OffsetDependentTypesHelper(this);
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy(MultiGraphFactory factory) {
        return new LessThanQualifierHierarchy(factory);
    }

    class LessThanQualifierHierarchy extends GraphQualifierHierarchy {

        public LessThanQualifierHierarchy(MultiGraphFactory f) {
            super(f, BOTTOM);
        }

        @Override
        public boolean isSubtype(AnnotationMirror subAnno, AnnotationMirror superAnno) {
            List<String> subList = getLessThanExpressions(subAnno);
            List<String> superList = getLessThanExpressions(superAnno);
            if (subList == null) {
                return true;
            } else if (superList == null) {
                return false;
            }

            return subList.containsAll(superList);
        }

        @Override
        public AnnotationMirror leastUpperBound(AnnotationMirror a1, AnnotationMirror a2) {
            if (isSubtype(a1, a2)) {
                return a2;
            } else if (isSubtype(a2, a1)) {
                return a1;
            }

            List<String> a1List = getLessThanExpressions(a1);
            List<String> a2List = getLessThanExpressions(a2);
            List<String> lub = new ArrayList<>(a1List);
            lub.retainAll(a2List);

            return createLessThanQualifier(lub);
        }

        @Override
        public AnnotationMirror greatestLowerBound(AnnotationMirror a1, AnnotationMirror a2) {
            if (isSubtype(a1, a2)) {
                return a1;
            } else if (isSubtype(a2, a1)) {
                return a2;
            }

            List<String> a1List = getLessThanExpressions(a1);
            List<String> a2List = getLessThanExpressions(a2);
            List<String> glb = new ArrayList<>(a1List);
            glb.addAll(a2List);

            return createLessThanQualifier(glb);
        }
    }

    /** @return Is left less than right? */
    public boolean isLessThan(Tree left, String right) {
        AnnotatedTypeMirror leftATM = getAnnotatedType(left);
        if (leftATM.getAnnotations().size() != 1) {
            return false;
        }
        List<String> expressions =
                getLessThanExpressions(leftATM.getAnnotations().iterator().next());
        if (expressions == null) {
            return true;
        }
        return expressions.contains(right);
    }

    /** @return {@code smaller < bigger}, using information from the Value Checker */
    public boolean isLessThanByValue(Tree smaller, String bigger, TreePath path) {
        Long smallerValue = IndexUtil.getMinValue(smaller, getValueAnnotatedTypeFactory());
        if (smallerValue == null) {
            return false;
        }

        OffsetEquation offsetEquation = OffsetEquation.createOffsetFromJavaExpression(bigger);
        if (offsetEquation.isInt()) {
            // bigger is an int literal
            return smallerValue < offsetEquation.getInt();
        }
        // If bigger is "expression + literal", then smaller < expression + literal
        // can be reduced to smaller - literal < expression + literal - literal
        smallerValue = smallerValue - offsetEquation.getInt();
        offsetEquation =
                offsetEquation.copyAdd(
                        '-', OffsetEquation.createOffsetForInt(offsetEquation.getInt()));

        long minValueOfBigger = getMinValueFromString(offsetEquation.toString(), smaller, path);
        return smallerValue < minValueOfBigger;
    }

    /** Returns the minimum value of {@code expressions} at {@code tree}. */
    private long getMinValueFromString(String expression, Tree tree, TreePath path) {
        AnnotationMirror intRange = null;
        try {
            intRange =
                    getValueAnnotatedTypeFactory()
                            .getAnnotationFromJavaExpressionString(
                                    expression, tree, path, IntRange.class);
        } catch (FlowExpressionParseException e) {
        }
        if (intRange != null) {
            return ValueAnnotatedTypeFactory.getRange(intRange).from;
        }
        AnnotationMirror intValue = null;
        try {
            intValue =
                    getValueAnnotatedTypeFactory()
                            .getAnnotationFromJavaExpressionString(
                                    expression, tree, path, IntVal.class);
        } catch (FlowExpressionParseException e) {
        }
        if (intValue != null) {

            List<Long> possibleValues = ValueAnnotatedTypeFactory.getIntValues(intValue);
            return Collections.min(possibleValues);
        }
        return Long.MIN_VALUE;
    }

    /** @return Is left less than or equal to right? */
    public boolean isLessThanOrEqual(Tree left, String right) {
        AnnotatedTypeMirror leftATM = getAnnotatedType(left);
        if (leftATM.getAnnotations().size() != 1) {
            return false;
        }
        List<String> expressions =
                getLessThanExpressions(leftATM.getAnnotations().iterator().next());
        if (expressions == null) {
            // left is bottom so it is always less than right.
            return true;
        }
        if (expressions.contains(right)) {
            return true;
        }
        // {@code @LessThan("end + 1")} is equivalent to {@code @LessThanOrEqual("end")}.
        for (String expression : expressions) {
            if (expression.endsWith(" + 1")) {
                if (expression.substring(0, expression.length() - 4).equals(right)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a sorted, modifiable list of expressions that {@code expression} is less than. If the
     * {@code expression} is annotated with {@link LessThanBottom}, null is returned.
     */
    public List<String> getLessThanExpressions(ExpressionTree expression) {
        AnnotatedTypeMirror annotatedTypeMirror = getAnnotatedType(expression);
        if (annotatedTypeMirror.getAnnotations().size() != 1) {
            return new ArrayList<>();
        }
        return getLessThanExpressions(annotatedTypeMirror.getAnnotations().iterator().next());
    }

    /**
     * Creates a less than qualifier given the expressions.
     *
     * <p>If expressions is null, {@link LessThanBottom} is returned. If expressions is empty,
     * {@link LessThanUnknown} is returned. Otherwise, {@code LessThanUnknown(expressions)} is
     * returned.
     */
    public AnnotationMirror createLessThanQualifier(List<String> expressions) {
        if (expressions == null) {
            return BOTTOM;
        } else if (expressions.isEmpty()) {
            return UNKNOWN;
        } else {
            AnnotationBuilder builder = new AnnotationBuilder(processingEnv, LessThan.class);
            builder.setValue("value", expressions);
            return builder.build();
        }
    }

    /** Returns {@code @LessThan(expression)} */
    public AnnotationMirror createLessThanQualifier(String expression) {
        return createLessThanQualifier(Collections.singletonList(expression));
    }

    /**
     * Returns a modifiable list of expressions in the annotation sorted. If the annotation is
     * {@link LessThanBottom}, return null. If the annotation is {@link LessThanUnknown} return the
     * empty list.
     */
    public static List<String> getLessThanExpressions(AnnotationMirror annotation) {
        if (AnnotationUtils.areSameByClass(annotation, LessThanBottom.class)) {
            return null;
        } else if (AnnotationUtils.areSameByClass(annotation, LessThanUnknown.class)) {
            return new ArrayList<>();
        } else {
            List<String> list =
                    AnnotationUtils.getElementValueArray(annotation, "value", String.class, true);
            return list;
        }
    }
}
