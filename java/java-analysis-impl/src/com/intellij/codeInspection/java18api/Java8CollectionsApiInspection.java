/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.java18api;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInspection.BaseJavaBatchLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ui.SingleCheckboxOptionsPanel;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.EquivalenceChecker;
import com.siyeh.ig.psiutils.ExpressionUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collection;

/**
 * @author Dmitry Batkovich
 */
public class Java8CollectionsApiInspection extends BaseJavaBatchLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance(Java8CollectionsApiInspection.class);

  public boolean myReportContainsCondition;

  @Nullable
  @Override
  public JComponent createOptionsPanel() {
    return new SingleCheckboxOptionsPanel("Report when \'containsKey\' is used in condition (may change semantics)", this,
                                          "myReportContainsCondition");
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.isLanguageLevel8OrHigher(holder.getFile())) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(PsiMethodCallExpression expression) {
        super.visitMethodCallExpression(expression);
        PsiElement nameElement = expression.getMethodExpression().getReferenceNameElement();
        if(nameElement != null && expression.getArgumentList().getExpressions().length == 2 &&
          "sort".equals(nameElement.getText())) {
          PsiMethod method = expression.resolveMethod();
          if(method != null) {
            PsiClass containingClass = method.getContainingClass();
            if(containingClass != null && CommonClassNames.JAVA_UTIL_COLLECTIONS.equals(containingClass.getQualifiedName())) {
              //noinspection DialogTitleCapitalization
              holder.registerProblem(nameElement, QuickFixBundle.message("java.8.collections.api.inspection.sort.description"),
                                     new ReplaceWithListSortFix());
            }
          }
        }
      }

      @Override
      public void visitConditionalExpression(PsiConditionalExpression expression) {
        final ConditionInfo conditionInfo = extractConditionInfo(expression.getCondition());
        if (conditionInfo == null) return;
        final PsiExpression thenExpression = expression.getThenExpression();
        final PsiExpression elseExpression = expression.getElseExpression();
        if (thenExpression == null || elseExpression == null) return;
        analyzeCorrespondenceOfPutAndGet(conditionInfo.isInverted() ? thenExpression : elseExpression,
                                         conditionInfo.isInverted() ? elseExpression : thenExpression,
                                         conditionInfo.getQualifier(), conditionInfo.getContainsKey(),
                                         holder, expression);
      }

      @Override
      public void visitIfStatement(PsiIfStatement statement) {
        handleGetWithVariable(holder, statement);
        final PsiExpression condition = statement.getCondition();
        final ConditionInfo conditionInfo = extractConditionInfo(condition);
        if (conditionInfo == null) return;
        PsiStatement maybeGetBranch = conditionInfo.isInverted() ? statement.getElseBranch() : statement.getThenBranch();
        if (maybeGetBranch instanceof PsiBlockStatement) {
          final PsiStatement[] getBranchStatements = ((PsiBlockStatement)maybeGetBranch).getCodeBlock().getStatements();
          if (getBranchStatements.length > 1) return;
          maybeGetBranch = getBranchStatements.length == 0 ? null : getBranchStatements[0];
        }
        final PsiStatement branch = conditionInfo.isInverted() ? statement.getThenBranch() : statement.getElseBranch();
        final PsiStatement maybePutStatement;
        if (branch instanceof PsiBlockStatement) {
          final PsiStatement[] statements = ((PsiBlockStatement)branch).getCodeBlock().getStatements();
          if (statements.length != 1) return;
          maybePutStatement = statements[statements.length - 1];
        }
        else {
          maybePutStatement = branch;
        }
        if (maybePutStatement != null) {
          analyzeCorrespondenceOfPutAndGet(maybePutStatement, maybeGetBranch, conditionInfo.getQualifier(), conditionInfo.getContainsKey(),
                                           holder, statement);
        }
      }

      private PsiMethodCallExpression tryExtractMapGetCall(PsiReferenceExpression target, PsiElement element) {
        if(element instanceof PsiDeclarationStatement) {
          PsiDeclarationStatement declaration = (PsiDeclarationStatement)element;
          PsiElement[] elements = declaration.getDeclaredElements();
          if(elements.length > 0) {
            PsiElement lastDeclaration = elements[elements.length - 1];
            if(lastDeclaration instanceof PsiLocalVariable && lastDeclaration == target.resolve()) {
              PsiLocalVariable var = (PsiLocalVariable)lastDeclaration;
              PsiExpression initializer = PsiUtil.skipParenthesizedExprDown(var.getInitializer());
              if (initializer instanceof PsiMethodCallExpression &&
                  isJavaUtilMapMethodWithName((PsiMethodCallExpression)initializer, "get")) {
                return (PsiMethodCallExpression)initializer;
              }
            }
          }
        }
        if(element instanceof PsiExpressionStatement) {
          PsiExpression expression = ((PsiExpressionStatement)element).getExpression();
          if(expression instanceof PsiAssignmentExpression) {
            PsiAssignmentExpression assignment = (PsiAssignmentExpression)expression;
            PsiExpression lValue = assignment.getLExpression();
            if (lValue instanceof PsiReferenceExpression &&
                EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(target, lValue)) {
              PsiExpression rValue = PsiUtil.skipParenthesizedExprDown(assignment.getRExpression());
              if (rValue instanceof PsiMethodCallExpression &&
                  isJavaUtilMapMethodWithName((PsiMethodCallExpression)rValue, "get")) {
                return (PsiMethodCallExpression)rValue;
              }
            }
          }
        }
        return null;
      }

      private void handleGetWithVariable(ProblemsHolder holder, PsiIfStatement statement) {
        if(statement.getElseBranch() != null) return;
        PsiExpression condition = statement.getCondition();
        if(!(condition instanceof PsiBinaryExpression)) return;
        PsiBinaryExpression binOp = (PsiBinaryExpression)condition;
        if(!binOp.getOperationTokenType().equals(JavaTokenType.EQEQ)) return;
        PsiExpression value = getValueComparedWithNull(binOp);
        if(!(value instanceof PsiReferenceExpression)) return;
        PsiElement previous = PsiTreeUtil.skipSiblingsBackward(statement, PsiWhiteSpace.class, PsiComment.class);
        PsiMethodCallExpression getCall = tryExtractMapGetCall((PsiReferenceExpression)value, previous);
        if(getCall == null) return;
        PsiExpression[] getArguments = getCall.getArgumentList().getExpressions();
        if(getArguments.length != 1) return;
        PsiStatement thenBranch = ControlFlowUtils.stripBraces(statement.getThenBranch());
        if(thenBranch instanceof PsiExpressionStatement) {
          PsiExpression expression = ((PsiExpressionStatement)thenBranch).getExpression();
          if(expression instanceof PsiAssignmentExpression) {
            PsiExpression lValue = ((PsiAssignmentExpression)expression).getLExpression();
            PsiExpression rValue = ((PsiAssignmentExpression)expression).getRExpression();
            if (ExpressionUtils.isSimpleExpression(rValue) && EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(lValue, value)) {
              holder.registerProblem(getCall, QuickFixBundle.message("java.8.collections.api.inspection.description"),
                                     new ReplaceWithGetOrDefaultFix());
            }
          }
        }
      }
    };
  }

  @Nullable
  private ConditionInfo extractConditionInfo(PsiExpression condition) {
    final ConditionInfo info = extractConditionInfoIfGet(condition);
    if (info != null) {
      return info;
    }
    return !myReportContainsCondition ? null : extractConditionInfoIfContains(condition);
  }

  @Nullable
  private static PsiExpression getValueComparedWithNull(PsiBinaryExpression binOp) {
    if(!binOp.getOperationTokenType().equals(JavaTokenType.EQEQ) &&
      !binOp.getOperationTokenType().equals(JavaTokenType.NE)) return null;
    PsiExpression left = binOp.getLOperand();
    PsiExpression right = binOp.getROperand();
    if(ExpressionUtils.isNullLiteral(right)) return left;
    if(ExpressionUtils.isNullLiteral(left)) return right;
    return null;
  }

  @Nullable
  private static ConditionInfo extractConditionInfoIfGet(PsiExpression condition) {
    if(!(condition instanceof PsiBinaryExpression)) return null;
    PsiBinaryExpression binOp = (PsiBinaryExpression)condition;
    PsiExpression operand = getValueComparedWithNull(binOp);
    if (!(operand instanceof PsiMethodCallExpression)) return null;
    final PsiMethodCallExpression maybeGetCall = (PsiMethodCallExpression)operand;
    if (!isJavaUtilMapMethodWithName(maybeGetCall, "get")) return null;
    final PsiExpression[] arguments = maybeGetCall.getArgumentList().getExpressions();
    if (arguments.length != 1) return null;
    PsiExpression getQualifier = maybeGetCall.getMethodExpression().getQualifierExpression();
    PsiExpression keyExpression = arguments[0];
    return new ConditionInfo(getQualifier, keyExpression, binOp.getOperationTokenType().equals(JavaTokenType.EQEQ));
  }

  @Nullable
  private static ConditionInfo extractConditionInfoIfContains(PsiExpression condition) {
    boolean inverted = false;
    final PsiMethodCallExpression conditionMethodCall;
    if (condition instanceof PsiPrefixExpression) {
      final PsiPrefixExpression prefixExpression = (PsiPrefixExpression)condition;
      if (JavaTokenType.EXCL.equals(prefixExpression.getOperationSign().getTokenType()) &&
          prefixExpression.getOperand() instanceof PsiMethodCallExpression) {
        conditionMethodCall = (PsiMethodCallExpression)prefixExpression.getOperand();
        inverted = true;
      }
      else {
        return null;
      }
    }
    else if (condition instanceof PsiMethodCallExpression) {
      conditionMethodCall = (PsiMethodCallExpression)condition;
    }
    else {
      return null;
    }
    if (!isJavaUtilMapMethodWithName(conditionMethodCall, "containsKey")) {
      return null;
    }
    final PsiExpression containsQualifier = conditionMethodCall.getMethodExpression().getQualifierExpression();
    if (containsQualifier == null) {
      return null;
    }
    final PsiExpression[] expressions = conditionMethodCall.getArgumentList().getExpressions();
    if (expressions.length != 1) {
      return null;
    }
    PsiExpression containsKey = expressions[0];
    return new ConditionInfo(containsQualifier, containsKey, inverted);
  }

  private static void analyzeCorrespondenceOfPutAndGet(@NotNull PsiElement adjustedElseBranch,
                                                       @Nullable PsiElement adjustedThenBranch,
                                                       @Nullable PsiExpression containsQualifier,
                                                       @Nullable PsiExpression containsKey,
                                                       @NotNull ProblemsHolder holder,
                                                       @NotNull PsiElement context) {
    final PsiElement maybePutMethodCall;
    final PsiElement maybeGetMethodCall;
    if (adjustedThenBranch == null) {
      maybeGetMethodCall = null;
      if (adjustedElseBranch instanceof PsiExpressionStatement) {
        final PsiExpression expression = ((PsiExpressionStatement)adjustedElseBranch).getExpression();
        if (expression instanceof PsiMethodCallExpression && isJavaUtilMapMethodWithName((PsiMethodCallExpression)expression, "put")) {
          maybePutMethodCall = expression;
        }
        else {
          maybePutMethodCall = null;
        }
      }
      else {
        maybePutMethodCall = null;
      }
    }
    else {
      if (adjustedElseBranch instanceof PsiStatement && adjustedThenBranch instanceof PsiStatement) {
        final EquivalenceChecker.Decision decision = EquivalenceChecker.getCanonicalPsiEquivalence().statementsAreEquivalentDecision((PsiStatement)adjustedElseBranch,
                                                                                                                                     (PsiStatement)adjustedThenBranch);
        maybePutMethodCall = decision.getLeftDiff();
        maybeGetMethodCall = decision.getRightDiff();
      }
      else {
        maybePutMethodCall = adjustedElseBranch;
        maybeGetMethodCall = adjustedThenBranch;
      }
    }
    if (maybePutMethodCall instanceof PsiMethodCallExpression &&
        (maybeGetMethodCall == null || maybeGetMethodCall instanceof PsiMethodCallExpression)) {
      final PsiMethodCallExpression putMethodCall = (PsiMethodCallExpression)maybePutMethodCall;
      final PsiMethodCallExpression getMethodCall = (PsiMethodCallExpression)maybeGetMethodCall;
      final PsiExpression putQualifier = putMethodCall.getMethodExpression().getQualifierExpression();
      final PsiExpression getQualifier = getMethodCall == null ? null : getMethodCall.getMethodExpression().getQualifierExpression();
      if ((getMethodCall == null || EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(putQualifier, getQualifier)) &&
          EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(putQualifier, containsQualifier) &&
          isJavaUtilMapMethodWithName(putMethodCall, "put") &&
          (getMethodCall == null || isJavaUtilMapMethodWithName(getMethodCall, "get"))) {

        PsiExpression getArgument;
        if (getMethodCall != null) {
          final PsiExpression[] arguments = getMethodCall.getArgumentList().getExpressions();
          if (arguments.length != 1) {
            return;
          }
          getArgument = arguments[0];
        }
        else {
          getArgument = null;
        }

        final PsiExpression[] putArguments = putMethodCall.getArgumentList().getExpressions();
        if (putArguments.length != 2) {
          return;
        }
        PsiExpression putKeyArgument = putArguments[0];

        if (EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(containsKey, putKeyArgument) &&
            (getArgument == null || EquivalenceChecker.getCanonicalPsiEquivalence().expressionsAreEquivalent(getArgument, putKeyArgument))) {
          holder.registerProblem(context, QuickFixBundle.message("java.8.collections.api.inspection.description"),
                                 new ReplaceWithMapPutIfAbsentFix(putMethodCall));
        }
      }
    }
  }

  private static boolean isJavaUtilMapMethodWithName(@NotNull PsiMethodCallExpression methodCallExpression, @NotNull String expectedName) {
    if (!expectedName.equals(methodCallExpression.getMethodExpression().getReferenceName())) {
      return false;
    }
    final PsiMethod method = methodCallExpression.resolveMethod();
    if (method == null) return false;
    PsiMethod[] superMethods = method.findDeepestSuperMethods();
    if (superMethods.length == 0) {
      superMethods = new PsiMethod[]{method};
    }
    return StreamEx.of(superMethods).map(PsiMember::getContainingClass).nonNull().map(PsiClass::getQualifiedName)
      .has(CommonClassNames.JAVA_UTIL_MAP);
  }

  private static class ConditionInfo {
    private final PsiExpression myQualifier;
    private final PsiExpression myContainsKey;
    private final boolean myInverted;

    private ConditionInfo(PsiExpression qualifier, PsiExpression containsKey, boolean inverted) {
      myQualifier = qualifier;
      myContainsKey = containsKey;
      myInverted = inverted;
    }

    public PsiExpression getQualifier() {
      return myQualifier;
    }

    public PsiExpression getContainsKey() {
      return myContainsKey;
    }

    public boolean isInverted() {
      return myInverted;
    }
  }

  private static class ReplaceWithListSortFix implements LocalQuickFix {
    @Nls
    @NotNull
    @Override
    public String getName() {
      return getFamilyName();
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return QuickFixBundle.message("java.8.collections.api.inspection.sort.fix.name");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      PsiMethodCallExpression methodCallExpression = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
      if(methodCallExpression != null) {
        PsiExpression[] args = methodCallExpression.getArgumentList().getExpressions();
        if(args.length == 2) {
          PsiExpression list = args[0];
          PsiExpression comparator = args[1];
          String replacement = list.getText()+".sort("+comparator.getText()+")";
          if (!FileModificationService.getInstance().preparePsiElementForWrite(element.getContainingFile())) return;
          methodCallExpression
            .replace(JavaPsiFacade.getElementFactory(project).createExpressionFromText(replacement, methodCallExpression));
        }
      }
    }
  }

  private static class ReplaceWithGetOrDefaultFix implements LocalQuickFix {
    @Nls
    @NotNull
    @Override
    public String getName() {
      return getFamilyName();
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return "Replace with 'getOrDefault' method call";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiElement element = descriptor.getStartElement();
      if (!(element instanceof PsiMethodCallExpression)) return;
      PsiMethodCallExpression getCall = (PsiMethodCallExpression)element;
      if(!isJavaUtilMapMethodWithName(getCall, "get")) return;
      PsiStatement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
      PsiElement ifStatement = PsiTreeUtil.skipSiblingsForward(statement, PsiWhiteSpace.class, PsiComment.class);
      if (!(ifStatement instanceof PsiIfStatement)) return;
      PsiElement nameElement = getCall.getMethodExpression().getReferenceNameElement();
      if(nameElement == null) return;
      PsiExpression[] args = getCall.getArgumentList().getExpressions();
      if(args.length != 1) return;
      PsiStatement thenBranch = ControlFlowUtils.stripBraces(((PsiIfStatement)ifStatement).getThenBranch());
      if(!(thenBranch instanceof PsiExpressionStatement)) return;
      PsiExpression expression = ((PsiExpressionStatement)thenBranch).getExpression();
      if(!(expression instanceof PsiAssignmentExpression)) return;
      PsiExpression defaultValue = ((PsiAssignmentExpression)expression).getRExpression();
      if(!ExpressionUtils.isSimpleExpression(defaultValue)) return;
      if (!FileModificationService.getInstance().preparePsiElementForWrite(element.getContainingFile())) return;
      Collection<PsiComment> comments = ContainerUtil.map(PsiTreeUtil.findChildrenOfType(ifStatement, PsiComment.class),
                                                                comment -> (PsiComment)comment.copy());
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      nameElement.replace(factory.createIdentifier("getOrDefault"));
      getCall.getArgumentList().add(defaultValue);
      ifStatement.delete();
      comments.forEach(comment -> statement.getParent().addBefore(comment, statement));
    }
  }
}