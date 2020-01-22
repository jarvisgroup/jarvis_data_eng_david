package ca.jrvs.apps.trading.service;

import static sun.awt.geom.Curve.round;

import ca.jrvs.apps.trading.dao.*;
import ca.jrvs.apps.trading.model.*;
import java.lang.reflect.Field;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TraderAccountService {

  private static final Logger logger = LoggerFactory.getLogger(TraderAccountService.class);

  private TraderDao traderDao;
  private AccountDao accountDao;
  private PositionDao positionDao;
  private SecurityOrderDao securityOrderDao;

  private DecimalFormat df;

  @Autowired
  public TraderAccountService(TraderDao traderDao, AccountDao accountDao,
      PositionDao positionDao, SecurityOrderDao securityOrderDao) {
    this.traderDao = traderDao;
    this.accountDao = accountDao;
    this.positionDao = positionDao;
    this.securityOrderDao = securityOrderDao;
  }

  /**
   * Create a new trader and initialize a new account with 0 amount. - validate user input (all
   * fields must be non empty) - create a trader - create an amount create, setup, and return a new
   * traderAccountView
   * <p>
   * Assumption: to simplify the logic, each trader has only one account where traderId ==
   * accountId
   *
   * @param trader cannot be null. All fields cannot be null except for id (auto-generated by db)
   * @return traderAccountView
   * @throws IllegalArgumentException if a trader has null fields or ids is not null
   */
  public TraderAccountView createTraderAndAccount(Trader trader) {

    trader = traderExceptionCheck(trader);

    Trader savedTrader = traderDao.save(trader);
    Account newAccount = new Account();
    newAccount.setTrader_id(savedTrader.getId());
    newAccount.setAmount(0.0d);
    Account savedAccount = accountDao.save(newAccount);

    return new TraderAccountView(savedTrader, savedAccount);
  }

  /**
   * Check exceptions helper function
   */
  public Trader traderExceptionCheck(Trader trader) {
    if (trader == null) {
      throw new IllegalArgumentException("trader is null");
    }
    if (trader.getId() != null) {
      trader.setId(null);
    }

    for (Field field : Trader.class.getDeclaredFields()) {
      if (field.getName().equals("id")) {
        continue;
      }
      try {
        if (field.get(field) == null) {
          throw new IllegalArgumentException("Field: " + field.getName() + " is null");
        }
      } catch (IllegalAccessException e) {
        logger.debug("Access exception when getting fields of trader", e);
      }
    }
    return trader;
  }

  /**
   * A trader can be deleted iff it has no open position and 0 cash balance - validate traderID -
   * get trader account by traderId and check account balance - get positions by accountId and check
   * positions - delete all securityOrders, account, trader (in this order)
   *
   * @param traderId must not be null
   * @throws IllegalArgumentException if traderId is null or not found or unable to delete
   */
  public void deleteTraderById(Integer traderId) {

    transactionSetup(traderId,0.0d);

    List<Account> returnedAccounts = accountDao.findById(traderId, true);
    List<Position> allPositions = new ArrayList<Position>();

    for (Account account : returnedAccounts) {
      if (account.getAmount() > 0.0) {
        throw new IllegalArgumentException(
            "Trader Account: " + account.getId() + " has " + account.getAmount()
                + ". Need 0.0 to delete");
      }
      List<Position> positions = positionDao.findPosById(account.getId());
      if (positions != null) {
        throw new IllegalArgumentException(account.getId() + " has position for account");
      }
    }

    for(Account account: returnedAccounts) {
      securityOrderDao.deleteById(account.getId());
    }
    accountDao.deleteById(traderId, true);
    traderDao.deleteById(traderId);
  }

  /**
   * Deposit a fund to an account by traderId - validate user input - account =
   * accountDao.findByTraderId - accountDao.updateAmountById
   *
   * @param traderId must not be null
   * @param fund     must be greater than 0
   * @return updated Account
   * @throws IllegalArgumentException if traderId is not null or not found, and fund is less or
   *                                  equal to 0
   */
  public Account deposit(Integer traderId, Double fund) {
    transactionSetup(traderId,fund);
    List<Account> returnedAccounts = accountDao.findById(traderId, true);
    Account returnAccount = null;
    for(Account account: returnedAccounts) {
      String strAmount = df.format(account.getAmount()+fund);
      Double newAmount = Double.valueOf(strAmount);
      account.setAmount(newAmount);
      returnAccount = accountDao.save(account);
    }
    return returnAccount;
  }

  /**
   * Withdraw a fund to an account by traderId
   * <p>
   * - validate user input - account = accountDao.findByTraderId - accountDao.updateAmountById
   *
   * @param traderId trader ID
   * @param fund     amount can't be 0
   * @return updated Account
   * @throws IllegalArgumentException if traderId is null or not found fund is less or equal to 0,
   *                                  and insufficient fund
   */
  public Account withdraw(Integer traderId, Double fund) {
    transactionSetup(traderId,fund);

    List<Account> returnedAccounts = accountDao.findById(traderId, true);
    Account returnAccount = null;
    for(Account account: returnedAccounts) {
      String strAmount = df.format(account.getAmount()-fund);
      Double newAmount = Double.valueOf(strAmount);
      if (newAmount < -0.001) {
        throw new IllegalArgumentException("Cannot have negative balance: " + newAmount);
      }
      account.setAmount(newAmount);
      returnAccount = accountDao.save(account);
    }
    return returnAccount;
  }

  public void transactionSetup(Integer traderId, Double fund) {
    df = new DecimalFormat("#.##");
    df.setRoundingMode(RoundingMode.CEILING);

    if (traderId == null) {
      throw new IllegalArgumentException("traderId is null");
    }

    if (!traderDao.existsById(traderId)) {
      throw new IllegalArgumentException("traderId does not exist in database");
    }

    if (fund <= -0.001) {
      throw new IllegalArgumentException("Fund is: " + fund + ". Fund must be greater or equal to 0");
    }
  }
}
