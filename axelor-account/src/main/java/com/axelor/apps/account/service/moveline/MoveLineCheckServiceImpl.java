package com.axelor.apps.account.service.moveline;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.exception.AccountExceptionMessage;
import com.axelor.apps.account.service.AccountService;
import com.axelor.apps.account.service.analytic.AnalyticDistributionTemplateService;
import com.axelor.apps.account.service.analytic.AnalyticMoveLineService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.apache.commons.collections.CollectionUtils;

public class MoveLineCheckServiceImpl implements MoveLineCheckService {
  protected AccountService accountService;
  protected AnalyticMoveLineService analyticMoveLineService;
  protected AnalyticDistributionTemplateService analyticDistributionTemplateService;
  protected MoveLineToolService moveLineToolService;

  @Inject
  public MoveLineCheckServiceImpl(
      AccountService accountService,
      AnalyticMoveLineService analyticMoveLineService,
      AnalyticDistributionTemplateService analyticDistributionTemplateService,
      MoveLineToolService moveLineToolService) {
    this.accountService = accountService;
    this.analyticMoveLineService = analyticMoveLineService;
    this.analyticDistributionTemplateService = analyticDistributionTemplateService;
    this.moveLineToolService = moveLineToolService;
  }

  @Override
  public void checkAnalyticByTemplate(MoveLine moveLine) throws AxelorException {
    if (moveLine.getAnalyticDistributionTemplate() != null) {
      analyticMoveLineService.validateLines(
          moveLine.getAnalyticDistributionTemplate().getAnalyticDistributionLineList());

      if (!analyticMoveLineService.validateAnalyticMoveLines(moveLine.getAnalyticMoveLineList())) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            I18n.get(AccountExceptionMessage.INVALID_ANALYTIC_MOVE_LINE));
      }

      analyticDistributionTemplateService.validateTemplatePercentages(
          moveLine.getAnalyticDistributionTemplate());
    }
  }

  @Override
  public void checkAnalyticAxes(MoveLine moveLine) throws AxelorException {
    if (moveLine.getAccount() != null) {
      accountService.checkAnalyticAxis(
          moveLine.getAccount(), moveLine.getAnalyticDistributionTemplate());
    }
  }

  @Override
  public void checkDebitCredit(MoveLine moveLine) throws AxelorException {
    if (moveLine.getCredit().signum() == 0 && moveLine.getDebit().signum() == 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(AccountExceptionMessage.MOVE_LINE_NO_DEBIT_CREDIT));
    }

    if (moveLine.getCredit().signum() < 0 || moveLine.getDebit().signum() < 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(AccountExceptionMessage.MOVE_LINE_NEGATIVE_DEBIT_CREDIT));
    }
  }

  @Override
  public void checkDates(Move move) throws AxelorException {
    if (!CollectionUtils.isEmpty(move.getMoveLineList())) {
      for (MoveLine moveline : move.getMoveLineList()) {
        moveLineToolService.checkDateInPeriod(move, moveline);
      }
    }
  }

  @Override
  public void checkAnalyticAccount(List<MoveLine> moveLineList) throws AxelorException {
    Objects.requireNonNull(moveLineList);
    for (MoveLine moveLine : moveLineList) {
      if (moveLine != null && moveLine.getAccount() != null) {
        accountService.checkAnalyticAxis(
            moveLine.getAccount(), moveLine.getAnalyticDistributionTemplate());
      }
    }
  }
}
