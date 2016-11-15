package com.kalessil.phpStorm.phpInspectionsEA.inspectors.semanticalAnalysis;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.jetbrains.php.codeInsight.PhpScopeHolder;
import com.jetbrains.php.codeInsight.controlFlow.PhpControlFlowUtil;
import com.jetbrains.php.codeInsight.controlFlow.instructions.PhpAccessVariableInstruction;
import com.jetbrains.php.codeInsight.controlFlow.instructions.PhpEntryPointInstruction;
import com.jetbrains.php.lang.documentation.phpdoc.psi.impl.PhpDocCommentImpl;
import com.jetbrains.php.lang.lexer.PhpTokenTypes;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.*;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpElementVisitor;
import com.kalessil.phpStorm.phpInspectionsEA.openApi.BasePhpInspection;
import com.kalessil.phpStorm.phpInspectionsEA.utils.ExpressionSemanticUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;


public class OneTimeUseVariablesInspector extends BasePhpInspection {
    private static final String messagePattern = "Variable $%v% is redundant";

    @NotNull
    public String getShortName() {
        return "OneTimeUseVariablesInspection";
    }

    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new BasePhpElementVisitor() {
            void checkOneTimeUse(@NotNull PhpPsiElement construct, @NotNull Variable argument) {
                final String variableName = argument.getName();
                /* verify preceding expression (assignment needed) */
                if (
                    !StringUtil.isEmpty(variableName) && null != construct.getPrevPsiSibling() &&
                    construct.getPrevPsiSibling().getFirstChild() instanceof AssignmentExpressionImpl
                ) {
                    final AssignmentExpressionImpl assign = (AssignmentExpressionImpl) construct.getPrevPsiSibling().getFirstChild();
                    /* ensure it's not a self-assignment */
                    if (assign instanceof SelfAssignmentExpression) {
                        return;
                    }

                    /* ensure variables are the same */
                    final PhpPsiElement assignVariable = assign.getVariable();
                    final PsiElement assignValue       = ExpressionSemanticUtil.getExpressionTroughParenthesis(assign.getValue());
                    if (null != assignValue && assignVariable instanceof Variable) {
                        final String assignVariableName = assignVariable.getName();
                        if (StringUtil.isEmpty(assignVariableName) || !assignVariableName.equals(variableName)) {
                            return;
                        }

                        /* check if variable as a function/use(...) parameter by reference */
                        final Function function = ExpressionSemanticUtil.getScope(construct);
                        if (null != function) {
                            for (Parameter param: function.getParameters()) {
                                if (param.isPassByRef() && param.getName().equals(variableName)) {
                                    return;
                                }
                            }

                            LinkedList<Variable> useList = ExpressionSemanticUtil.getUseListVariables(function);
                            if (null != useList) {
                                for (Variable param: useList) {
                                    if (!param.getName().equals(variableName)) {
                                        continue;
                                    }

                                    /* detect parameters by reference in use clause */
                                    PsiElement ampersandCandidate = param.getPrevSibling();
                                    if (ampersandCandidate instanceof PsiWhiteSpace) {
                                        ampersandCandidate = ampersandCandidate.getPrevSibling();
                                    }
                                    if (null != ampersandCandidate && ampersandCandidate.getText().equals("&")) {
                                        return;
                                    }
                                }
                                useList.clear();
                            }
                        }

                        /* too long return/throw statements can be decoupled as a variable */
                        final boolean isConstructDueToLongAssignment = assign.getText().length() > 80;
                        if (isConstructDueToLongAssignment) {
                            return;
                        }

                        /* heavy part, find usage inside function/method to analyze multiple writes */
                        final PhpScopeHolder parentScope = ExpressionSemanticUtil.getScope(assign);
                        if (null != parentScope) {
                            final PhpEntryPointInstruction objEntryPoint = parentScope.getControlFlow().getEntryPoint();
                            final PhpAccessVariableInstruction[] usages  = PhpControlFlowUtil.getFollowingVariableAccessInstructions(objEntryPoint, variableName, false);

                            int countWrites = 0;
                            for (PhpAccessVariableInstruction oneCase: usages) {
                                countWrites += oneCase.getAccess().isWrite() ? 1 : 0;
                                if (countWrites > 1) {
                                    return;
                                }
                            }
                        }

                        final String message    = messagePattern.replace("%v%", variableName);
                        final TheLocalFix fixer = new TheLocalFix(assign.getParent(), argument, assignValue);
                        holder.registerProblem(assignVariable, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fixer);
                    }
                }
            }

            public void visitPhpReturn(PhpReturn returnStatement) {
                /* if function returning reference, do not inspect returns */
                final Function callable = ExpressionSemanticUtil.getScope(returnStatement);
                if (null != callable && null != callable.getNameIdentifier()) {
                    /* is defined like returning reference */
                    PsiElement referenceCandidate = callable.getNameIdentifier().getPrevSibling();
                    if (referenceCandidate instanceof PsiWhiteSpace) {
                        referenceCandidate = referenceCandidate.getPrevSibling();
                    }
                    if (null != referenceCandidate && PhpTokenTypes.opBIT_AND == referenceCandidate.getNode().getElementType()) {
                        return;
                    }
                }

                /* regular function, check one-time use variables */
                final PsiElement argument = ExpressionSemanticUtil.getExpressionTroughParenthesis(returnStatement.getArgument());
                if (argument instanceof PhpPsiElement) {
                    final Variable variable = this.getVariable((PhpPsiElement) argument);
                    if (null != variable) {
                        checkOneTimeUse(returnStatement, variable);
                    }
                }
            }

            public void visitPhpMultiassignmentExpression(MultiassignmentExpression multiassignmentExpression) {
                final PsiElement firstChild = multiassignmentExpression.getFirstChild();
                if (null != firstChild && PhpTokenTypes.kwLIST == firstChild.getNode().getElementType()) {
                    final Variable variable = this.getVariable(multiassignmentExpression.getValue());
                    final PsiElement parent = multiassignmentExpression.getParent();
                    if (null != variable && parent instanceof StatementImpl) {
                        checkOneTimeUse((PhpPsiElement) parent, variable);
                    }
                }
            }

            public void visitPhpThrow(PhpThrow throwStatement) {
                final PsiElement argument = ExpressionSemanticUtil.getExpressionTroughParenthesis(throwStatement.getArgument());
                if (argument instanceof PhpPsiElement) {
                    final Variable variable = this.getVariable((PhpPsiElement) argument);
                    if (null != variable) {
                        checkOneTimeUse(throwStatement, variable);
                    }
                }
            }

            @Nullable
            private Variable getVariable(@Nullable PhpPsiElement expression) {
                if (null == expression) {
                    return null;
                }

                if (expression instanceof Variable) {
                    return (Variable) expression;
                }

                if (expression instanceof FieldReferenceImpl) {
                    final FieldReferenceImpl propertyAccess = (FieldReferenceImpl) expression;
                    if (propertyAccess.getFirstChild() instanceof Variable) {
                        return (Variable) propertyAccess.getFirstChild();
                    }
                }

                /* instanceof passes child classes as well, what isn't correct */
                if (expression.getClass() == PhpExpressionImpl.class) {
                    return getVariable(expression.getFirstPsiChild());
                }

                return null;
            }
        };
    }

    private static class TheLocalFix implements LocalQuickFix {
        private SmartPsiElementPointer<PsiElement> assignment;
        private SmartPsiElementPointer<PsiElement> value;
        private SmartPsiElementPointer<Variable> variable;

        TheLocalFix(@NotNull PsiElement assignment, @NotNull Variable variable, @NotNull PsiElement value) {
            super();
            final SmartPointerManager factory = SmartPointerManager.getInstance(value.getProject());

            this.assignment = factory.createSmartPsiElementPointer(assignment, assignment.getContainingFile());
            this.variable   = factory.createSmartPsiElementPointer(variable, variable.getContainingFile());
            this.value      = factory.createSmartPsiElementPointer(value, value.getContainingFile());
        }

        @NotNull
        @Override
        public String getName() {
            return "Inline value";
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            final PsiElement assignment = this.assignment.getElement();
            final Variable variable     = this.variable.getElement();
            final PsiElement value      = this.value.getElement();
            if (null == assignment || null == variable || null == value) {
                return;
            }

            /* delete preceding PhpDoc */
            final PhpPsiElement previous = ((StatementImpl) assignment).getPrevPsiSibling();
            if (previous instanceof PhpDocCommentImpl) {
                previous.delete();
            }

            /* delete space after the method */
            PsiElement nextExpression = assignment.getNextSibling();
            if (nextExpression instanceof PsiWhiteSpace) {
                nextExpression.delete();
            }

            /* delete assignment itself */
            variable.replace(value);
            assignment.delete();
        }
    }
}
