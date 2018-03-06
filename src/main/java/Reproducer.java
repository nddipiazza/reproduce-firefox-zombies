import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriverLogLevel;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.GeckoDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Reproducer {
  static String baseUrl = null;
  static String firefoxExe = null;
  static int threadCount = 50;
  public static void main(String[] args) throws Exception {
    if (args.length > 0) {
      baseUrl = args[0];
      if (baseUrl.endsWith("/")) {
        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
      }
    }
    if (args.length > 1) {
      threadCount = Integer.parseInt(args[1]);
    }
    if (args.length > 2) {
      firefoxExe = args[2];
    }
    List<Thread> ts = new ArrayList<>();
    for (int i=0; i<threadCount; ++i) {
      ts.add(
          new Thread(() -> {
            try {
              loadUrls();
            } catch (IOException e) {
              e.printStackTrace();
            }
          })
      );
    }
    while (true) {
      for (Thread t : ts) {
        t.start();
      }
      for (Thread t : ts) {
        t.join();
      }
    }
  }

  /**
   * First wait for the doucment.readyState to be complete.
   *
   * Then attempt to wait for all pending ajax requests to finish.
   */
  public static void waitForPageToBeReady(WebDriver driver) {
    new WebDriverWait(driver, 20000L).until(wd -> ((JavascriptExecutor) wd).executeScript(
        "return document.readyState").equals("complete"));
    int secondsToWait = 20;
    try {
      if (driver instanceof JavascriptExecutor) {
        Long remainingAjaxConnections = null;
        JavascriptExecutor jsDriver = (JavascriptExecutor) driver;
        for (int i = 0; i < secondsToWait; i++) {
          Object numberOfAjaxConnections = jsDriver.executeScript("return window._lucidworksWebConnectorPendingHttpRequests");
          // return should be a number
          if (numberOfAjaxConnections instanceof Long) {
            Long n = (Long) numberOfAjaxConnections;
            remainingAjaxConnections = n.longValue();
            System.out.println("Still waiting for " + remainingAjaxConnections + " connections to complete");
            if (remainingAjaxConnections <= 0L) {
              break;
            }
          }
          Thread.sleep(1000L);
        }
        if (remainingAjaxConnections == null) {
          System.out.println("Waited 20 seconds for ajax connections to finish loading on a page but window.openHTTPs did not " +
              "return any connection counts...");
        }
      } else {
        System.out.println("Web driver: " + driver + " cannot execute javascript");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.out.println("Could not wait for pending ajax requests due to error");
      e.printStackTrace();
    }
  }

  private static void loadUrls() throws IOException {
    DriverService driverService = new GeckoDriverService.Builder()
        .usingDriverExecutable(new File("geckodriver"))
        .build();
    driverService.start();
    DesiredCapabilities capabilities = DesiredCapabilities.firefox();
    FirefoxOptions options = new FirefoxOptions();
    if (firefoxExe != null) {
      options.setBinary(firefoxExe);
    }
    options.setLogLevel(FirefoxDriverLogLevel.ERROR);
    options.setHeadless(true);

    FirefoxProfile firefoxProfile = new FirefoxProfile();

    // Do not allow popups while browsing otherwise the can end up leaving stranded firefox instances everywhere.
    firefoxProfile.setPreference("dom.popup_maximum", 0);
    firefoxProfile.setPreference("privacy.popups.showBrowserMessage", false);
    firefoxProfile.setPreference("dom.disable_beforeunload", true);

    firefoxProfile.addExtension(new File("requestcounter.xpi"));

    options.setProfile(firefoxProfile);

    capabilities.setCapability(FirefoxOptions.FIREFOX_OPTIONS, options);
    RemoteWebDriver driver = new RemoteWebDriver(driverService.getUrl(), capabilities);
    driver.manage().timeouts().implicitlyWait(20000L, TimeUnit.MILLISECONDS);
    driver.manage().timeouts().pageLoadTimeout(20000L, TimeUnit.MILLISECONDS);
    driver.manage().timeouts().setScriptTimeout(20000L, TimeUnit.MILLISECONDS);
    while (true) {
      int nextPage = new Random().nextInt(10000);
      driver.get((baseUrl == null ? "http://localhost:7001" : baseUrl) + "/page" + nextPage + ".html");
      waitForPageToBeReady(driver);
      if (driver.getPageSource().contains("still waiting for reload")) {
        System.out.println("failed to wait for content to be ready for page.");
      }
    }
  }
}
