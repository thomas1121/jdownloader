//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import jd.PluginWrapper;
import jd.config.ConfigContainer;
import jd.config.ConfigEntry;
import jd.config.Property;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.nutils.JDHash;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.parser.html.InputField;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.CaptchaException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.keycaptcha.KeyCaptcha;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.scripting.JavaScriptEngineFactory;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "easybytez.com" }, urls = { "https?://(www\\.)?easybytez\\.com/((vid)?embed\\-)?[a-z0-9]{12}" })
@SuppressWarnings("deprecation")
public class EasyBytezCom extends PluginForHost {
    // Site Setters
    // primary website url, take note of redirects
    public final String          COOKIE_HOST                  = "http://easybytez.com";
    // domain names used within download links.
    private final String         DOMAINS                      = "(easybytez\\.com|easybytez\\.co|easybytez\\.to|zingload\\.com|easyload\\.to|ezbytez\\.com|ebytez\\.com)";
    private final String         PASSWORDTEXT                 = "<br><b>Passwor(d|t):</b> <input";
    private final String         MAINTENANCE                  = ">This server is in maintenance mode";
    private final String         dllinkRegex                  = "https?://(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|([\\w\\-]+\\.)?" + DOMAINS + ")(:\\d{1,5})?/(files(/(dl|download))?|d|cgi\\-bin/dl\\.cgi)/(\\d+/)?([a-z0-9]+/){1,4}[^/<>\r\n\t]+";
    private final String         dllinkRegex_2                = "https?://(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|([\\w\\-]+\\.)?[a-z0-9\\-\\.]+)(:\\d{1,5})?/(files(/(dl|download))?|d|cgi\\-bin/dl\\.cgi)/(\\d+/)?([a-z0-9]+/){1,4}[^/<>\r\n\t]+";
    private final boolean        supportsHTTPS                = false;
    private final boolean        enforcesHTTPS                = false;
    private final boolean        useRUA                       = true;
    private final boolean        useAltLinkCheck              = false;
    private final boolean        useVidEmbed                  = false;
    private final boolean        useAltEmbed                  = false;
    private final boolean        useAltExpire                 = true;
    private final long           useLoginIndividual           = 6 * 3480000l;
    private final boolean        waitTimeSkipableReCaptcha    = true;
    private final boolean        waitTimeSkipableSolveMedia   = false;
    private final boolean        waitTimeSkipableKeyCaptcha   = false;
    private final boolean        captchaSkipableSolveMedia    = false;
    // Connection Management
    // note: CAN NOT be negative or zero! (ie. -1 or 0) Otherwise math sections fail. .:. use [1-20]
    private static AtomicInteger totalMaxSimultanFreeDownload = new AtomicInteger(5);

    // DEV NOTES
    // XfileShare Version 3.0.8.5
    // last XfileSharingProBasic compare :: 2.6.2.1
    // captchatype: null
    // other: no redirects
    // mods:fetchAccountInfo [Treat expired premium accounts as free accounts], dllinkRegex
    // other: ex-sister site, zingload.com
    private void setConstants(final Account account) {
        if (account != null && account.getBooleanProperty("free")) {
            // free account
            chunks = 1;
            resumes = false;
            acctype = "Free Account";
            directlinkproperty = "freelink2";
        } else if (account != null && !account.getBooleanProperty("free")) {
            // prem account
            chunks = 0;
            resumes = true;
            acctype = "Premium Account";
            directlinkproperty = "premlink";
        } else {
            // non account
            chunks = 1;
            resumes = false;
            acctype = "Non Account";
            directlinkproperty = "freelink";
        }
    }

    private boolean allowsConcurrent(final Account account) {
        if (account != null && account.getBooleanProperty("free")) {
            // free account
            return false;
        } else if (account != null && !account.getBooleanProperty("free")) {
            // prem account
            return true;
        } else {
            // non account
            return false;
        }
    }

    public boolean hasAutoCaptcha() {
        return true;
    }

    public boolean hasCaptcha(final DownloadLink downloadLink, final jd.plugins.Account acc) {
        if (acc == null) {
            /* no account, yes we can expect captcha */
            return true;
        }
        if (Boolean.TRUE.equals(acc.getBooleanProperty("free"))) {
            /* free accounts also have captchas */
            return true;
        }
        return false;
    }

    /**
     * @author raztoki
     *
     * @category 'Experimental', Mods written July 2012 - 2013
     */
    public EasyBytezCom(PluginWrapper wrapper) {
        super(wrapper);
        setConfigElements();
        this.enablePremium(COOKIE_HOST + "/premium.html");
    }

    /**
     * defines custom browser requirements.
     */
    public Browser prepBrowser(final Browser prepBr) {
        HashMap<String, String> map = null;
        synchronized (cloudflareCookies) {
            map = new HashMap<String, String>(cloudflareCookies);
        }
        if (!map.isEmpty()) {
            for (final Map.Entry<String, String> cookieEntry : map.entrySet()) {
                final String key = cookieEntry.getKey();
                final String value = cookieEntry.getValue();
                prepBr.setCookie(this.getHost(), key, value);
            }
        }
        if (useRUA) {
            if (userAgent.get() == null) {
                /* we first have to load the plugin, before we can reference it */
                JDUtilities.getPluginForHost("mediafire.com");
                userAgent.set(jd.plugins.hoster.MediafireCom.stringUserAgent());
            }
            prepBr.getHeaders().put("User-Agent", userAgent.get());
        }
        prepBr.getHeaders().put("Accept-Language", "en-gb, en;q=0.8");
        prepBr.setCookie(COOKIE_HOST, "lang", "english");
        // required for native cloudflare support, without the need to repeat requests.
        try {
            /* not available in old stable */
            prepBr.setAllowedResponseCodes(new int[] { 503 });
        } catch (Throwable e) {
        }
        return prepBr;
    }

    private static AtomicLong LASTREQUESTFILEINFORMATION        = new AtomicLong(0);
    private final int         waitBetweenRequestFileInformation = 250;

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws Exception {
        // make sure the downloadURL protocol is of site ability and user preference
        synchronized (LASTREQUESTFILEINFORMATION) {
            try {
                final long last = System.currentTimeMillis() - LASTREQUESTFILEINFORMATION.get();
                if (last < waitBetweenRequestFileInformation && last >= 0) {
                    Thread.sleep(waitBetweenRequestFileInformation - last);
                }
                correctDownloadLink(downloadLink);
                fuid = new Regex(downloadLink.getDownloadURL(), "([a-z0-9]{12})$").getMatch(0);
                br.setFollowRedirects(true);
                prepBrowser(br);
                String[] fileInfo = new String[2];
                if (useAltLinkCheck) {
                    altAvailStat(downloadLink, fileInfo);
                }
                getPage(downloadLink.getDownloadURL());
                if (br.getURL().matches(".+(\\?|&)op=login(.*)?")) {
                    ArrayList<Account> accounts = AccountController.getInstance().getAllAccounts(this.getHost());
                    Account account = null;
                    if (accounts != null && accounts.size() != 0) {
                        Iterator<Account> it = accounts.iterator();
                        while (it.hasNext()) {
                            Account n = it.next();
                            if (n.isEnabled() && n.isValid()) {
                                account = n;
                                break;
                            }
                        }
                    }
                    if (account != null) {
                        login(account, false);
                        getPage(downloadLink.getDownloadURL());
                    } else {
                        altAvailStat(downloadLink, fileInfo);
                    }
                }
                if (cbr.containsHTML("No such file|>File Not Found<|>The file was removed by|Reason for deletion:\n|<li>The file ((?:has )?expired|deleted by (its owner|administration)|of the above link no longer exists\\.|contained illegal contents? and was deleted)|>The file of the above link no longer exists\\.")) {
                    throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
                }
                if (cbr.containsHTML(MAINTENANCE)) {
                    downloadLink.getLinkStatus().setStatusText(MAINTENANCEUSERTEXT);
                    return AvailableStatus.TRUE;
                }
                // scan the first page
                scanInfo(downloadLink, fileInfo);
                // scan the second page. filesize[1] isn't mission critical
                if (inValidate(fileInfo[0])) {
                    Form download1 = getFormByKey(cbr, "op", "download1");
                    if (download1 != null) {
                        download1 = cleanForm(download1);
                        download1.remove("method_premium");
                        sendForm(download1);
                        scanInfo(downloadLink, fileInfo);
                    }
                    if (inValidate(fileInfo[0]) && inValidate(fileInfo[1])) {
                        logger.warning("Possible plugin error, trying fail over!");
                        altAvailStat(downloadLink, fileInfo);
                    }
                }
                if (inValidate(fileInfo[0])) {
                    if (cbr.containsHTML("You have reached the download(-| )limit")) {
                        logger.warning("Waittime detected, please reconnect to make the linkchecker work!");
                        return AvailableStatus.UNCHECKABLE;
                    }
                    logger.warning("filename equals null, throwing \"plugin defect\"");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                fileInfo[0] = fileInfo[0].replaceAll("(</?b>|\\.html)", "");
                downloadLink.setName(fileInfo[0].trim());
                if (getAvailableStatus(downloadLink).toString().equals("UNCHECKED")) {
                    downloadLink.setAvailable(true);
                }
                if (!inValidate(fileInfo[1])) {
                    downloadLink.setDownloadSize(SizeFormatter.getSize(fileInfo[1]));
                }
                return getAvailableStatus(downloadLink);
            } finally {
                LASTREQUESTFILEINFORMATION.set(System.currentTimeMillis());
            }
        }
    }

    private String[] scanInfo(final DownloadLink downloadLink, final String[] fileInfo) {
        // standard traits from base page
        if (inValidate(fileInfo[0])) {
            fileInfo[0] = cbr.getRegex("You have requested.*?https?://(www\\.)?" + DOMAINS + "/" + fuid + "/(.*?)</font>").getMatch(2);
            if (inValidate(fileInfo[0])) {
                fileInfo[0] = cbr.getRegex("fname\"( type=\"hidden\")? value=\"(.*?)\"").getMatch(1);
                if (inValidate(fileInfo[0])) {
                    fileInfo[0] = cbr.getRegex("<h2>Download File(.*?)</h2>").getMatch(0);
                    if (inValidate(fileInfo[0])) {
                        // can cause new line finds, so check if it matches.
                        // fileInfo[0] = cbr.getRegex("Download File:? ?(<[^>]+> ?)+?([^<>\"']+)").getMatch(1);
                        // traits from download1 page below.
                        if (inValidate(fileInfo[0])) {
                            fileInfo[0] = cbr.getRegex("Filename:? ?(<[^>]+> ?)+?([^<>\"']+)").getMatch(1);
                            // next two are details from sharing box
                            if (inValidate(fileInfo[0])) {
                                fileInfo[0] = cbr.getRegex("<textarea[^\r\n]+>([^\r\n]+) - [\\d\\.]+ (KB|MB|GB)</a></textarea>").getMatch(0);
                                if (inValidate(fileInfo[0])) {
                                    fileInfo[0] = cbr.getRegex("<textarea[^\r\n]+>[^\r\n]+\\]([^\r\n]+) - [\\d\\.]+ (KB|MB|GB)\\[/URL\\]").getMatch(0);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (inValidate(fileInfo[1])) {
            fileInfo[1] = cbr.getRegex("\\(([0-9]+ bytes)\\)").getMatch(0);
            if (inValidate(fileInfo[1])) {
                fileInfo[1] = cbr.getRegex("</font>[ ]+\\(([^<>\"'/]+)\\)(.*?)</font>").getMatch(0);
                if (inValidate(fileInfo[1])) {
                    fileInfo[1] = cbr.getRegex("<span class=\"size\">(\\d+(\\.\\d+)? ?(KB|MB|GB))</span>").getMatch(0);
                    if (inValidate(fileInfo[1])) {
                        fileInfo[1] = cbr.getRegex("(\\d+(\\.\\d+)? ?(KB|MB|GB))").getMatch(0);
                        if (inValidate(fileInfo[1])) {
                            try {
                                // only needed in rare circumstances
                                // altAvailStat(downloadLink, fileInfo);
                            } catch (Exception e) {
                            }
                        }
                    }
                }
            }
        }
        return fileInfo;
    }

    /**
     * Provides alternative linkchecking method for a single link at a time. Can be used as generic failover, though kinda pointless as this
     * method doesn't give filename...
     *
     */
    private String[] altAvailStat(final DownloadLink downloadLink, final String[] fileInfo) throws Exception {
        Browser alt = new Browser();
        prepBrowser(alt);
        // cloudflare initial support is within getPage.. otherwise not needed.
        alt.getPage(COOKIE_HOST.replaceFirst("https?://", getProtocol()) + "/?op=checkfiles");
        alt.postPage("/?op=checkfiles", "op=checkfiles&process=Check+URLs&list=" + downloadLink.getDownloadURL());
        String[] linkInformation = alt.getRegex(">" + downloadLink.getDownloadURL() + "</td><td style=\"color:[^;]+;\">(\\w+)</td><td>([^<>]+)?</td>").getRow(0);
        if (linkInformation != null && linkInformation[0].equalsIgnoreCase("found")) {
            downloadLink.setAvailable(true);
            if (!inValidate(linkInformation[1]) && inValidate(fileInfo[1])) {
                fileInfo[1] = linkInformation[1];
            }
        } else {
            // not found! <td>link</td><td style="color:red;">Not found!</td><td></td>
            downloadLink.setAvailable(false);
        }
        if (!inValidate(fuid) && inValidate(fileInfo[0])) {
            fileInfo[0] = fuid;
        }
        return fileInfo;
    }

    @SuppressWarnings("unused")
    private void doFree(final DownloadLink downloadLink, final Account account) throws Exception, PluginException {
        if (checkShowFreeDialog(getHost())) {
            showFreeDialog(getHost());
        }
        if (account != null) {
            logger.info(account.getUser() + " @ " + acctype + " -> Free Download");
        } else {
            logger.info("Guest @ " + acctype + " -> Free Download");
        }
        // redirects need to be disabled for getDllink
        br.setFollowRedirects(false);
        // First, bring up saved final links
        dllink = checkDirectLink(downloadLink);
        // Second, check for streaming links on the first page
        if (inValidate(dllink)) {
            getDllink();
        }
        // Third, do they provide video hosting?
        if (inValidate(dllink) && (useVidEmbed || (useAltEmbed && downloadLink.getName().matches(".+\\.(asf|avi|flv|m4u|m4v|mov|mkv|mp4|mpeg4?|mpg|ogm|vob|wmv|webm)$")))) {
            final Browser obr = br.cloneBrowser();
            final Browser obrc = cbr.cloneBrowser();
            if (useVidEmbed) {
                getPage("/vidembed-" + fuid);
                getDllink();
                if (inValidate(dllink) && useAltEmbed) {
                    // this will set referrer info back
                    br = obr.cloneBrowser();
                }
            }
            if (inValidate(dllink) && useAltEmbed) {
                // alternative embed format
                getPage("/embed-" + fuid + ".html");
                getDllink();
            }
            if (inValidate(dllink)) {
                logger.warning("Failed to find 'embed dllink', trying normal download method.");
                br = obr;
                cbr = obrc;
            }
        }
        // Fourth, continue like normal.
        if (inValidate(dllink)) {
            checkErrors(downloadLink, account, false);
            Form download1 = getFormByKey(cbr, "op", "download1");
            if (download1 != null) {
                // stable is lame, issue finding input data fields correctly. eg. closes at ' quotation mark - remove when jd2 goes stable!
                download1 = cleanForm(download1);
                // end of backward compatibility
                download1.remove("method_premium");
                sendForm(download1);
                checkErrors(downloadLink, account, false);
                getDllink();
            }
        }
        if (inValidate(dllink)) {
            Form dlForm = getFormByKey(cbr, "op", "download2");
            if (dlForm == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            // how many forms deep do you want to try.
            int repeat = 2;
            for (int i = 0; i <= repeat; i++) {
                dlForm = cleanForm(dlForm);
                // custom form inputs
                final long timeBefore = System.currentTimeMillis();
                if (cbr.containsHTML(PASSWORDTEXT)) {
                    logger.info("The downloadlink seems to be password protected.");
                    dlForm = handlePassword(dlForm, downloadLink);
                }
                /* Captcha START */
                dlForm = captchaForm(downloadLink, dlForm);
                /* Captcha END */
                if (!skipWaitTime) {
                    waitTime(timeBefore, downloadLink);
                }
                sendForm(dlForm);
                logger.info("Submitted DLForm");
                checkErrors(downloadLink, account, true);
                getDllink();
                if (inValidate(dllink) && (getFormByKey(cbr, "op", "download2") == null || i == repeat)) {
                    if (i == repeat) {
                        logger.warning("Exausted repeat count, after 'dllink == null'");
                    } else {
                        logger.warning("Couldn't find 'download2' and 'dllink == null'");
                    }
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                } else if (inValidate(dllink) && getFormByKey(cbr, "op", "download2") != null) {
                    dlForm = getFormByKey(cbr, "op", "download2");
                    continue;
                } else {
                    break;
                }
            }
        }
        if (!inValidate(passCode)) {
            downloadLink.setProperty("pass", passCode);
        }
        // Process usedHost within hostMap. We do it here so that we can probe if slots are already used before openDownload.
        controlHost(account, downloadLink, true);
        logger.info("Final downloadlink = " + dllink + " starting the download...");
        try {
            dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumes, chunks);
        } catch (UnknownHostException e) {
            // Try catch required otherwise plugin logic wont work as intended. Also prevents infinite loops when dns record is missing.
            // dump the saved host from directlinkproperty
            downloadLink.setProperty(directlinkproperty, Property.NULL);
            // remove usedHost slot from hostMap
            controlHost(account, downloadLink, false);
            logger.warning("DNS issue has occured!");
            e.printStackTrace();
            // int value of plugin property, as core error in current JD2 prevents proper retry handling.
            // TODO: remove when retry issues are resolved!
            int retry = downloadLink.getIntegerProperty("retry", 0);
            if (retry == 3) {
                downloadLink.setProperty("retry", Property.NULL);
                throw new PluginException(LinkStatus.ERROR_FATAL, "DNS issue cannot be resolved!");
            } else {
                retry++;
                downloadLink.setProperty("retry", retry);
                throw new PluginException(LinkStatus.ERROR_RETRY, 15000);
            }
        }
        if (dl.getConnection().getContentType().contains("html")) {
            if (dl.getConnection().getResponseCode() == 503 && br.getHttpConnection().getHeaderField("server") != null && br.getHttpConnection().getHeaderField("server").toLowerCase(Locale.ENGLISH).contains("nginx")) {
                controlSimHost(account);
                controlHost(account, downloadLink, false);
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Service unavailable. Try again later.", 15 * 60 * 1000l);
            } else {
                logger.warning("The final dllink seems not to be a file!");
                br.followConnection();
                correctBR();
                checkServerErrors();
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        } else {
            // we can not 'rename' filename once the download started, could be problematic!
            if (downloadLink.getDownloadCurrent() == 0) {
                fixFilename(downloadLink);
            }
            try {
                // add a download slot
                controlSlot(+1, account);
                // start the dl
                dl.startDownload();
            } finally {
                // remove usedHost slot from hostMap
                controlHost(account, downloadLink, false);
                // remove download slot
                controlSlot(-1, account);
            }
        }
    }

    /**
     * Removes patterns which could break the plugin due to fake/hidden HTML, or false positives caused by HTML comments.
     *
     * @throws Exception
     * @author raztoki
     */
    public void correctBR() throws Exception {
        String toClean = br.toString();
        ArrayList<String> regexStuff = new ArrayList<String>();
        // remove custom rules first!!! As html can change because of generic cleanup rules.
        // generic cleanup
        // this checks for fake or empty forms from original source and corrects
        for (final Form f : br.getForms()) {
            if (!f.containsHTML("(<input[^>]+type=\"submit\"(>|[^>]+(?!\\s*disabled\\s*)([^>]+>|>))|<input[^>]+type=\"button\"(>|[^>]+(?!\\s*disabled\\s*)([^>]+>|>))|<form[^>]+onSubmit=(\"|').*?(\"|')(>|[\\s\r\n][^>]+>)|" + dllinkRegex + ")")) {
                toClean = toClean.replace(f.getHtmlCode(), "");
            }
        }
        regexStuff.add("<!(--.*?--)>");
        regexStuff.add("(<\\s*(\\w+)\\s+[^>]*style\\s*=\\s*(\"|')(?:(?:[\\w:;\\s#-]*(visibility\\s*:\\s*hidden;|display\\s*:\\s*none;|font-size\\s*:\\s*0;)[\\w:;\\s#-]*)|font-size\\s*:\\s*0|visibility\\s*:\\s*hidden|display\\s*:\\s*none)\\3[^>]*(>.*?<\\s*/\\2[^>]*>|/\\s*>))");
        for (String aRegex : regexStuff) {
            String results[] = new Regex(toClean, aRegex).getColumn(0);
            if (results != null) {
                for (String result : results) {
                    toClean = toClean.replace(result, "");
                }
            }
        }
        cbr = br.cloneBrowser();
        cleanupBrowser(cbr, toClean);
    }

    private void getDllink() {
        dllink = br.getRedirectLocation();
        if (inValidate(dllink) || (!inValidate(dllink) && !dllink.matches(dllinkRegex) && !dllink.matches(dllinkRegex_2))) {
            dllink = regexDllink(cbr.toString());
            if (inValidate(dllink)) {
                final String cryptedScripts[] = cbr.getRegex("p\\}\\((.*?)\\.split\\('\\|'\\)").getColumn(0);
                if (cryptedScripts != null && cryptedScripts.length != 0) {
                    for (String crypted : cryptedScripts) {
                        decodeDownloadLink(crypted);
                        if (!inValidate(dllink)) {
                            break;
                        }
                    }
                }
            }
        }
    }

    private void waitTime(final long timeBefore, final DownloadLink downloadLink) throws PluginException {
        /** Ticket Time */
        String ttt = cbr.getRegex("id=\"countdown_str\">[^<>\"]+<span id=\"[^<>\"]+\"( class=\"[^<>\"]+\")?>([\n ]+)?(\\d+)([\n ]+)?</span>").getMatch(2);
        if (inValidate(ttt)) {
            ttt = cbr.getRegex("id=\"countdown_str\"[^>]+>Wait[^>]+>(\\d+)\\s?+</span>").getMatch(0);
        }
        if (!inValidate(ttt)) {
            // remove one second from past, to prevent returning too quickly.
            final long passedTime = ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
            final long tt = Long.parseLong(ttt) - passedTime;
            logger.info("WaitTime detected: " + ttt + " second(s). Elapsed Time: " + (passedTime > 0 ? passedTime : 0) + " second(s). Remaining Time: " + tt + " second(s)");
            if (tt > 0) {
                sleep(tt * 1000l, downloadLink);
            }
        }
    }

    private void checkErrors(final DownloadLink theLink, final Account account, final boolean checkAll) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (cbr.containsHTML(">Expired download session<")) {
                // This shouldn't ever happen....
                logger.warning("Expired download session, lets retry!");
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            if (cbr.containsHTML("Wrong captcha")) {
                logger.warning("Wrong Captcha response!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (cbr.containsHTML(">Skipped countdown<")) {
                logger.warning("Possible Plugin Error: Please report to JDownloader Development Team!");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Fatal countdown error (countdown skipped)");
            }
            // MUST BE LAST IF STATEMENT due to PASSWORDTEXT, as it could be displayed with wrong captcha && skipped countdown.
            if (cbr.containsHTML("Wrong password|" + PASSWORDTEXT)) {
                // handle password has failed in the past, additional try catching / resetting values
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                passCode = null;
                theLink.setProperty("pass", Property.NULL);
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password supplied");
            }
        }
        // monitor this
        // 12th april 2014: <font style="color:#d33;">You have reached the download-limit: 1024 Mb for last 1 days</font>
        if (cbr.containsHTML("You have reached the download(-| )limit[^<]+for last[^<]+")) {
            /*
             * Indication of when you've reached the max download limit for that given session! Usually shows how long the session was
             * recorded from x time (hours|days) which can trigger false positive below wait handling. As its only indication of what's
             * previous happened, as in past tense and not a wait time going forward... unknown wait time!
             */
            // if (account != null) {
            // logger.warning("Your account ( " + account.getUser() + " @ " + acctype +
            // " ) has been temporarily disabled for going over the download session limit. JDownloader parses HTML for error messages, if you believe this is not a valid response please confirm issue within your browser. If you can download within your browser please contact JDownloader Development Team, if you can not download in your browser please take the issue up with "
            // + this.getHost());
            // account.setProperty(Account.PROPERTY_TEMP_DISABLED_TIMEOUT, 60 * 60 * 1000l);
            // throw new PluginException(LinkStatus.ERROR_PREMIUM, "You've reached the download session limit!",
            // PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            // } else {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "You've reached the download session limit!", 60 * 60 * 1000l);
            // }
        }
        /** Wait time reconnect handling */
        if (cbr.containsHTML("You have to wait")) {
            // adjust this Regex to catch the wait time string for COOKIE_HOST
            String WAIT = cbr.getRegex("((You have to wait)[^<>]+)").getMatch(0);
            String tmphrs = new Regex(WAIT, "\\s+(\\d+)\\s+hours?").getMatch(0);
            if (inValidate(tmphrs)) {
                tmphrs = cbr.getRegex("You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            }
            String tmpmin = new Regex(WAIT, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (inValidate(tmpmin)) {
                tmpmin = cbr.getRegex("You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            }
            String tmpsec = new Regex(WAIT, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            String tmpdays = new Regex(WAIT, "\\s+(\\d+)\\s+days?").getMatch(0);
            if (inValidate(tmphrs) && inValidate(tmpmin) && inValidate(tmpsec) && inValidate(tmpdays)) {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                long years = 0, days = 0, hours = 0, minutes = 0, seconds = 0;
                if (!inValidate(tmpdays)) {
                    days = Integer.parseInt(tmpdays);
                }
                if (!inValidate(tmphrs)) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (!inValidate(tmpmin)) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (!inValidate(tmpsec)) {
                    seconds = Integer.parseInt(tmpsec);
                }
                long waittime = ((years * 86400000 * 365) + (days * 86400000) + (hours * 3600000) + (minutes * 60000) + (seconds * 1000));
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                /** Not enough wait time to reconnect->Wait and try again */
                if (waittime < 180000) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.allwait", ALLWAIT_SHORT), waittime);
                } else {
                    throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
                }
            }
        }
        if (cbr.containsHTML("You're using all download slots for IP")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (cbr.containsHTML("Error happened when generating Download Link")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l);
        }
        /** Error handling for account based restrictions */
        // non account && free account (you would hope..)
        final String an = "( can download files up to |This file reached max downloads limit|>The file you requested reached max downloads limit for Free Users)";
        // these errors imply an account been used already. So we assume (Free Account), which is the case for most sites.
        final String fa = "(Upgrade your account to download (bigger|larger) files)";
        // these errors imply (Premium Required) from the outset.
        final String pr = "(Please Buy Premium To download this file<|>This file is available for Premium Users only\\.<)";
        // let the fun begin!
        if (cbr.containsHTML(an + "|" + fa + "|" + pr)) {
            String msg = null;
            String fileSizeLimit = cbr.getRegex("You can download files up to(.*?)only").getMatch(0);
            if (!inValidate(fileSizeLimit)) {
                fileSizeLimit = " :: You can download files up to " + fileSizeLimit.trim();
            }
            if (account != null) {
                msg = account.getUser() + " @ " + acctype;
                if (account.getBooleanProperty("free", false) && cbr.containsHTML(an + "|" + fa + "|" + pr)) {
                    // free
                    msg += " :: Only downloadable via premium account." + (!inValidate(fileSizeLimit) ? fileSizeLimit : "");
                    theLink.setProperty("requiresPremiumAccount", true);
                } else {
                    // premium: different account required??
                    msg += " :: Not downloadable via your account type.";
                }
            } else {
                msg = "Guest @ " + acctype;
                if (cbr.containsHTML(pr)) {
                    msg += " :: Only downloadable via premium account." + (!inValidate(fileSizeLimit) ? fileSizeLimit : "");
                    theLink.setProperty("requiresPremiumAccount", true);
                } else if (cbr.containsHTML(an)) {
                    msg += " :: Only downloadable via premium or registered.";
                    theLink.setProperty("requiresAnyAccount", true);
                }
            }
            logger.warning(msg);
            try {
                throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
            } catch (final Throwable e) {
                if (e instanceof PluginException) {
                    throw (PluginException) e;
                }
            }
            throw new PluginException(LinkStatus.ERROR_FATAL, msg);
        }
        if (cbr.containsHTML(MAINTENANCE)) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, MAINTENANCEUSERTEXT, 2 * 60 * 60 * 1000l);
        }
    }

    private void checkServerErrors() throws NumberFormatException, PluginException {
        if (br.getHttpConnection() != null && br.getURL().matches(".+easybytez.com/404\\.html\\?.+")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 403) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 403", 10 * 60 * 1000l);
        } else if (cbr.containsHTML("No file")) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Server error");
        } else if (cbr.containsHTML("(File Not Found|<h1>404 Not Found</h1>|<h1>The page cannot be found</h1>)")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error", 30 * 60 * 1000l);
        } else if (cbr.containsHTML("Wrong IP")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error - server says 'Wrong IP'", 5 * 60 * 1000l);
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        try {
            // logic to manipulate full login.
            if (useLoginIndividual >= 1800000 && account.getStringProperty("lastlogin", null) != null && (System.currentTimeMillis() - useLoginIndividual <= Long.parseLong(account.getStringProperty("lastlogin")))) {
                login(account, false);
            } else {
                login(account, true);
            }
        } catch (final PluginException e) {
            if (cbr.containsHTML("Your IP is temporarily blocked due to 3 incorrect login attempts")) {
                ai.setStatus("Your IP is temporarily blocked due to 3 incorrect login attempts");
            }
            account.setValid(false);
            throw e;
        }
        // account disabled due to breach of terms and conditions
        if (cbr.containsHTML(">Your account was disabled due to violation of our Terms of Service\\.<")) {
            logger.warning("Hoster disabled account due to violation of hosters Terms of Service.");
            ai.setStatus("Hoster disabled account due to violation of hosters Terms of Service.");
            account.setValid(false);
            return ai;
        }
        // required for when we don't login fully.
        final String myAccount = "/?op=my_account";
        if (br.getURL() == null) {
            br.setFollowRedirects(true);
            getPage(COOKIE_HOST.replaceFirst("https?://", getProtocol()) + myAccount);
        } else if (!br.getURL().contains(myAccount)) {
            getPage(myAccount);
        }
        updateAccountInfo(account, ai, cbr);
        return ai;
    }

    public void updateAccountInfo(Account account, AccountInfo ai, Browser cbr) throws Exception {
        if (ai == null) {
            ai = account.getAccountInfo();
        }
        if (ai == null) {
            ai = new AccountInfo();
            account.setAccountInfo(ai);
        }
        final String space[] = cbr.getRegex(">Used space:</td>.*?<td.*?b>([0-9\\.]+) ?(KB|MB|GB|TB)?").getRow(0);
        if ((space != null && space.length != 0) && (!inValidate(space[0]) && !inValidate(space[1]))) {
            // free users it's provided by default
            ai.setUsedSpace(space[0] + " " + space[1]);
        } else if ((space != null && space.length != 0) && !inValidate(space[0])) {
            // premium users the Mb value isn't provided for some reason...
            ai.setUsedSpace(space[0] + "Mb");
        }
        account.setValid(true);
        String availabletraffic = cbr.getRegex("Traffic available.*?:</TD><TD><b>([^<>\"']+)</b>").getMatch(0);
        if (!inValidate(availabletraffic) && !availabletraffic.contains("nlimited") && !availabletraffic.equalsIgnoreCase(" Mb")) {
            availabletraffic = availabletraffic.trim();
            // need to set 0 traffic left, as getSize returns positive result, even when negative value supplied.
            if (!availabletraffic.startsWith("-")) {
                ai.setTrafficLeft(SizeFormatter.getSize(availabletraffic));
            } else {
                ai.setTrafficLeft(0);
            }
        } else {
            ai.setUnlimitedTraffic();
        }
        final String expireDay = cbr.getRegex("(\\d{1,2} (January|February|March|April|May|June|July|August|September|October|November|December) \\d{4})").getMatch(0);
        boolean isFree = true;
        if (!inValidate(expireDay)) {
            final long expire = new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).parse(expireDay).getTime();
            if (expire > 0) {
                ai.setValidUntil(expire);
                isFree = ai.isExpired();
            }
        }
        if (isFree) {
            ai.setValidUntil(-1);
            account.setProperty("free", true);
            ai.setStatus("Free Account");
            account.setMaxSimultanDownloads(0);
            account.setProperty("totalMaxSim", 5);
        } else {
            account.setProperty("free", false);
            ai.setStatus("Premium Account");
            account.setMaxSimultanDownloads(10);
            account.setProperty("totalMaxSim", 10);
        }
    }

    @SuppressWarnings("unchecked")
    public HashMap<String, String> login(final Account account, final boolean force) throws Exception {
        synchronized (account) {
            try {
                /** Load cookies */
                prepBrowser(br);
                final Object ret = account.getProperty("cookies", null);
                boolean acmatch = Encoding.urlEncode(account.getUser()).equals(account.getStringProperty("name", Encoding.urlEncode(account.getUser())));
                if (acmatch) {
                    acmatch = Encoding.urlEncode(account.getPass()).equals(account.getStringProperty("pass", Encoding.urlEncode(account.getPass())));
                }
                if (acmatch && ret != null && ret instanceof HashMap<?, ?> && !force) {
                    final HashMap<String, String> cookies = (HashMap<String, String>) ret;
                    if (account.isValid()) {
                        for (final Map.Entry<String, String> cookieEntry : cookies.entrySet()) {
                            final String key = cookieEntry.getKey();
                            final String value = cookieEntry.getValue();
                            this.br.setCookie(COOKIE_HOST, key, value);
                        }
                        return cookies;
                    }
                }
                br.setFollowRedirects(true);
                getPage(COOKIE_HOST.replaceFirst("https?://", getProtocol()));
                String loginPage = br.getRegex("class=\"login\"><a href=\"(http[^<>\"]*?)\"").getMatch(0);
                if (loginPage == null) {
                    loginPage = "http://www.easybytez.com/login2.html";
                }
                getPage(loginPage);
                Form loginform = br.getFormbyProperty("name", "FL");
                if (loginform == null) {
                    if ("de".equalsIgnoreCase(language)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin defekt, bitte den JDownloader Support kontaktieren!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nPlugin broken, please contact the JDownloader Support!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                loginform = cleanForm(loginform);
                loginform.put("login", Encoding.urlEncode(account.getUser()));
                loginform.put("password", Encoding.urlEncode(account.getPass()));
                loginform.put("redirect", Encoding.urlEncode("http://www.easybytez.com/"));
                loginform.remove(null);
                // check form for login captcha crap.
                DownloadLink dummyLink = new DownloadLink(null, "Account", this.getHost(), COOKIE_HOST, true);
                loginform = captchaForm(dummyLink, loginform);
                // end of check form for login captcha crap.
                sendForm(loginform);
                if (br.getCookie(COOKIE_HOST, "login") == null || br.getCookie(COOKIE_HOST, "xfss") == null) {
                    if ("de".equalsIgnoreCase(language)) {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nUngültiger Benutzername oder ungültiges Passwort!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_PREMIUM, "\r\nInvalid username/password!", PluginException.VALUE_ID_PREMIUM_DISABLE);
                    }
                }
                /** Save cookies */
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = this.br.getCookies(COOKIE_HOST);
                for (final Cookie c : add.getCookies()) {
                    cookies.put(c.getKey(), c.getValue());
                }
                account.setProperty("name", Encoding.urlEncode(account.getUser()));
                account.setProperty("pass", Encoding.urlEncode(account.getPass()));
                account.setProperty("cookies", cookies);
                account.setProperty("lastlogin", System.currentTimeMillis());
                return cookies;
            } catch (final PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.setProperty("cookies", Property.NULL);
                    account.setProperty("lastlogin", Property.NULL);
                }
                throw e;
            }
        }
    }

    private static final String NICE_HOST         = "easybytez.com";
    private static final String NICE_HOSTproperty = "easybytezcom";

    @Override
    public void handlePremium(final DownloadLink downloadLink, final Account account) throws Exception {
        setConstants(account);
        requestFileInformation(downloadLink);
        boolean fresh = false;
        Object after = null;
        synchronized (account) {
            Object before = account.getProperty("cookies", null);
            after = login(account, false);
            fresh = before != after;
            if (fresh) {
                final String myAccount = "/?op=my_account";
                if (br.getURL() == null) {
                    br.setFollowRedirects(true);
                    getPage(COOKIE_HOST.replaceFirst("https?://", getProtocol()) + myAccount);
                } else if (!br.getURL().contains(myAccount)) {
                    getPage(myAccount);
                }
                updateAccountInfo(account, account.getAccountInfo(), cbr);
            }
        }
        if (account.getBooleanProperty("free")) {
            getPage(downloadLink.getDownloadURL());
            // if the cached cookie expired, relogin.
            if ((br.getCookie(COOKIE_HOST, "login")) == null || br.getCookie(COOKIE_HOST, "xfss") == null) {
                synchronized (account) {
                    if (after == account.getProperty("cookies", null)) {
                        account.setProperty("cookies", Property.NULL);
                    }
                    if (fresh) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_RETRY);
                    }
                }
            }
            doFree(downloadLink, account);
        } else {
            br.setFollowRedirects(false);
            logger.info(account.getUser() + " @ " + acctype + " ->  Premium Download");
            dllink = checkDirectLink(downloadLink);
            if (inValidate(dllink)) {
                getPage(downloadLink.getDownloadURL());
                // required because we can't have redirects enabled for getDllink detection
                if (br.getRedirectLocation() != null && !br.getRedirectLocation().matches(dllinkRegex) && !br.getRedirectLocation().matches(dllinkRegex_2)) {
                    getPage(br.getRedirectLocation());
                }
                // if the cached cookie expired, relogin.
                if ((br.getCookie(COOKIE_HOST, "login")) == null || br.getCookie(COOKIE_HOST, "xfss") == null || (br.containsHTML(">Become Premium to") && br.containsHTML("download this file fast"))) {
                    synchronized (account) {
                        if (after == account.getProperty("cookies", null)) {
                            account.setProperty("cookies", Property.NULL);
                        }
                        if (fresh) {
                            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                        } else {
                            throw new PluginException(LinkStatus.ERROR_RETRY);
                        }
                    }
                }
                getDllink();
                if (inValidate(dllink)) {
                    checkErrors(downloadLink, account, false);
                    Form dlform = cbr.getFormbyProperty("name", "F1");
                    if (dlform == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    } else if (cbr.containsHTML(PASSWORDTEXT)) {
                        dlform = handlePassword(dlform, downloadLink);
                    }
                    sendForm(dlform);
                    checkErrors(downloadLink, account, true);
                    getDllink();
                    if (dllink == null) {
                        dllink = getDllink2(cbr);
                    }
                    if (inValidate(dllink)) {
                        /* Maybe server error */
                        if (cbr.containsHTML(">File Download Link Generated<")) {
                            logger.info(NICE_HOST + ": dllinknull_hmm");
                            int timesFailed = downloadLink.getIntegerProperty(NICE_HOSTproperty + "dllinknull_hmm", 0);
                            downloadLink.getLinkStatus().setRetryCount(0);
                            if (timesFailed <= 2) {
                                timesFailed++;
                                downloadLink.setProperty(NICE_HOSTproperty + "dllinknull_hmm", timesFailed);
                                logger.info(NICE_HOST + ": dllinknull_hmm -> Retrying");
                                throw new PluginException(LinkStatus.ERROR_RETRY, "dllinknull_hmm");
                            } else {
                                downloadLink.setProperty(NICE_HOSTproperty + "dllinknull_hmm", Property.NULL);
                                logger.info(NICE_HOST + ": dllinknull_hmm - Plugin might be defect!");
                                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                            }
                        }
                        /* Something went really wrong */
                        logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
            }
            if (!inValidate(passCode)) {
                downloadLink.setProperty("pass", passCode);
            }
            // Process usedHost within hostMap. We do it here so that we can probe if slots are already used before openDownload.
            controlHost(account, downloadLink, true);
            logger.info("Final downloadlink = " + dllink + " starting the download...");
            // Try catch required otherwise plugin logic wont work as intended. Also prevents infinite loops when dns record is missing.
            try {
                dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumes, chunks);
            } catch (UnknownHostException e) {
                // dump the saved host from directlinkproperty
                downloadLink.setProperty(directlinkproperty, Property.NULL);
                // remove usedHost slot from hostMap
                controlHost(account, downloadLink, false);
                logger.warning("DNS issue has occured!");
                e.printStackTrace();
                // int value of plugin property, as core error in current JD2 prevents proper retry handling.
                // TODO: remove when retry issues are resolved!
                int retry = downloadLink.getIntegerProperty("retry", 0);
                if (retry == 3) {
                    downloadLink.setProperty("retry", Property.NULL);
                    throw new PluginException(LinkStatus.ERROR_FATAL, "DNS issue cannot be resolved!");
                } else {
                    retry++;
                    downloadLink.setProperty("retry", retry);
                    throw new PluginException(LinkStatus.ERROR_RETRY, 15000);
                }
            }
            if (dl.getConnection().getContentType().contains("html")) {
                if (dl.getConnection().getResponseCode() == 503 && br.getHttpConnection().getHeaderField("server") != null && br.getHttpConnection().getHeaderField("server").toLowerCase(Locale.ENGLISH).contains("nginx")) {
                    controlSimHost(account);
                    controlHost(account, downloadLink, false);
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Service unavailable. Try again later.", 15 * 60 * 1000l);
                } else {
                    logger.warning("The final dllink seems not to be a file!");
                    br.followConnection();
                    correctBR();
                    checkServerErrors();
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            } else {
                // we can not 'rename' filename once the download started, could be problematic!
                if (downloadLink.getDownloadCurrent() == 0) {
                    fixFilename(downloadLink);
                }
                try {
                    // add a download slot
                    controlSlot(+1, account);
                    // start the dl
                    dl.startDownload();
                } finally {
                    // remove usedHost slot from hostMap
                    controlHost(account, downloadLink, false);
                    // remove download slot
                    controlSlot(-1, account);
                }
            }
        }
    }

    private String getDllink2(Browser br) {
        String ret = br.getRegex("<a href=\"(https?://wrk.*?)\"").getMatch(0);
        if (ret == null) {
            ret = br.getRegex("<a href=\"(https?://str\\d+.easybytes*?)\"").getMatch(0);
        }
        if (ret == null) {
            ret = br.getRegex("This direct link[^~]+?<a href=\"(https?:.*?)\"").getMatch(0);
        }
        return ret;
    }

    // ***************************************************************************************************** //
    // The components below doesn't require coder interaction, or configuration !
    public Browser                                                  cbr                    = new Browser();
    private String                                                  acctype                = null;
    private String                                                  directlinkproperty     = null;
    private String                                                  dllink                 = null;
    private String                                                  fuid                   = null;
    private String                                                  passCode               = null;
    private String                                                  usedHost               = null;
    private int                                                     chunks                 = 1;
    private boolean                                                 resumes                = false;
    private boolean                                                 skipWaitTime           = false;
    private final String                                            language               = System.getProperty("user.language");
    private final String                                            preferHTTPS            = "preferHTTPS";
    private final String                                            ALLWAIT_SHORT          = JDL.L("hoster.xfilesharingprobasic.errors.waitingfordownloads", "Waiting till new downloads can be started");
    private final String                                            MAINTENANCEUSERTEXT    = JDL.L("hoster.xfilesharingprobasic.errors.undermaintenance", "This server is under Maintenance");
    private static AtomicInteger                                    maxFree                = new AtomicInteger(1);
    private static AtomicInteger                                    maxPrem                = new AtomicInteger(1);
    // connections you can make to a given 'host' file server, this assumes each file server is setup identically.
    private static AtomicInteger                                    maxNonAccSimDlPerHost  = new AtomicInteger(20);
    private static AtomicInteger                                    maxFreeAccSimDlPerHost = new AtomicInteger(20);
    private static AtomicInteger                                    maxPremAccSimDlPerHost = new AtomicInteger(20);
    private static AtomicReference<String>                          userAgent              = new AtomicReference<String>(null);
    private static HashMap<String, String>                          cloudflareCookies      = new HashMap<String, String>();
    private static HashMap<Account, HashMap<String, AtomicInteger>> hostMap                = new HashMap<Account, HashMap<String, AtomicInteger>>();
    private static Object                                           CTRLLOCK               = new Object();

    /**
     * Rules to prevent new downloads from commencing
     *
     */
    public boolean canHandle(DownloadLink downloadLink, Account account) throws Exception {
        if (downloadLink.getBooleanProperty("requiresPremiumAccount", false) && (account == null || account.getBooleanProperty("free", false))) {
            // Prevent another download method of the same account type from starting, when downloadLink marked as requiring premium account
            // to download.
            return false;
        } else if (downloadLink.getBooleanProperty("requiresAnyAccount", false) && account == null) {
            // Prevent another non account download method from starting, when account been determined as required.
            return false;
        } else {
            return true;
        }
    }

    @SuppressWarnings("unused")
    public void setConfigElements() {
        if (supportsHTTPS && enforcesHTTPS) {
            // preferhttps setting isn't needed! lets make sure preferhttps setting removed.
            getPluginConfig().setProperty(preferHTTPS, Property.NULL);
            getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_LABEL, "This Host Provider enforces secure communication requests via 'https' over SSL/TLS"));
        } else if (supportsHTTPS && !enforcesHTTPS) {
            getConfig().addEntry(new ConfigEntry(ConfigContainer.TYPE_CHECKBOX, getPluginConfig(), preferHTTPS, JDL.L("plugins.hoster.xfileshare.preferHTTPS", "Enforce secure communication requests via 'https' over SSL/TLS")).setDefaultValue(false));
        } else {
            // lets make sure preferhttps setting removed when hoster or we disable the plugin https ability.
            getPluginConfig().setProperty(preferHTTPS, Property.NULL);
        }
    }

    /**
     * Corrects downloadLink.urlDownload().<br/>
     * <br/>
     * The following code respect the hoster supported protocols via plugin boolean settings and users config preference
     *
     * @author raztoki
     */
    @SuppressWarnings("unused")
    @Override
    public void correctDownloadLink(final DownloadLink downloadLink) {
        if ((supportsHTTPS && enforcesHTTPS) || (supportsHTTPS && getPluginConfig().getBooleanProperty(preferHTTPS, false))) {
            // does the site enforce the use of https?
            downloadLink.setUrlDownload(downloadLink.getDownloadURL().replaceFirst("http://", "https://"));
        } else if (!supportsHTTPS) {
            // link cleanup, but respect users protocol choosing.
            downloadLink.setUrlDownload(downloadLink.getDownloadURL().replaceFirst("https://", "http://"));
        }
        // strip video hosting url's to reduce possible duped links.
        downloadLink.setUrlDownload(downloadLink.getDownloadURL().replaceAll("/(vid)?embed-", "/"));
        // output the hostmask as we wish based on COOKIE_HOST url!
        String desiredHost = new Regex(COOKIE_HOST, "https?://([^/]+)").getMatch(0);
        String importedHost = new Regex(downloadLink.getDownloadURL(), "https?://([^/]+)").getMatch(0);
        downloadLink.setUrlDownload(downloadLink.getDownloadURL().replaceAll(importedHost, desiredHost));
    }

    @SuppressWarnings("unused")
    public String getProtocol() {
        if ((supportsHTTPS && enforcesHTTPS) || (supportsHTTPS && getPluginConfig().getBooleanProperty(preferHTTPS, false))) {
            return "https://";
        } else {
            return "http://";
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return maxFree.get();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        /* workaround for free/premium issue on stable 09581 */
        return maxPrem.get();
    }

    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/tos.html";
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink downloadLink) {
        downloadLink.setProperty("retry", Property.NULL);
        downloadLink.setProperty("captchaTries", Property.NULL);
        downloadLink.setProperty("requiresAnyAccount", Property.NULL);
        downloadLink.setProperty("requiresPremiumAccount", Property.NULL);
    }

    /**
     * Gets page <br />
     * - natively supports silly cloudflare anti DDoS crapola
     *
     * @author raztoki
     */
    public void getPage(final String page) throws Exception {
        if (page == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        try {
            br.getPage(page);
        } catch (Exception e) {
            if (e instanceof PluginException) {
                throw (PluginException) e;
            }
            // should only be picked up now if not JD2
            if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 503 && br.getHttpConnection().getHeaderField("server") != null && br.getHttpConnection().getHeaderField("server").toLowerCase(Locale.ENGLISH).contains("cloudflare-nginx")) {
                logger.warning("Cloudflare anti DDoS measures enabled, your version of JD can not support this. In order to go any further you will need to upgrade to JDownloader 2");
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Cloudflare anti DDoS measures enabled");
            } else {
                throw e;
            }
        }
        // prevention is better than cure
        if (br.getHttpConnection() != null && br.getHttpConnection().getResponseCode() == 503 && br.getHttpConnection().getHeaderField("server") != null && br.getHttpConnection().getHeaderField("server").toLowerCase(Locale.ENGLISH).contains("cloudflare-nginx")) {
            String host = new Regex(page, "https?://([^/]+)(:\\d+)?/").getMatch(0);
            Form cloudflare = br.getFormbyProperty("id", "ChallengeForm");
            if (cloudflare == null) {
                cloudflare = br.getFormbyProperty("id", "challenge-form");
            }
            if (cloudflare != null) {
                String math = br.getRegex("\\$\\('#jschl_answer'\\)\\.val\\(([^\\)]+)\\);").getMatch(0);
                if (math == null) {
                    math = br.getRegex("a\\.value = ([\\d\\-\\.\\+\\*/]+);").getMatch(0);
                }
                if (math == null) {
                    String variableName = br.getRegex("(\\w+)\\s*=\\s*\\$\\(\'#jschl_answer\'\\);").getMatch(0);
                    if (variableName != null) {
                        variableName = variableName.trim();
                    }
                    math = br.getRegex(variableName + "\\.val\\(([^\\)]+)\\)").getMatch(0);
                }
                if (math == null) {
                    logger.warning("Couldn't find 'math'");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                // use js for now, but change to Javaluator as the provided string doesn't get evaluated by JS according to Javaluator
                // author.
                ScriptEngineManager mgr = JavaScriptEngineFactory.getScriptEngineManager(this);
                ScriptEngine engine = mgr.getEngineByName("JavaScript");
                final long value = ((Number) engine.eval("(" + math + ") + " + host.length())).longValue();
                cloudflare.put("jschl_answer", value + "");
                Thread.sleep(5500);
                br.submitForm(cloudflare);
                if (br.getFormbyProperty("id", "ChallengeForm") != null || br.getFormbyProperty("id", "challenge-form") != null) {
                    logger.warning("Possible plugin error within cloudflare handling");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                // lets save cloudflare cookie to reduce the need repeat cloudFlare()
                final HashMap<String, String> cookies = new HashMap<String, String>();
                final Cookies add = br.getCookies(this.getHost());
                for (final Cookie c : add.getCookies()) {
                    if (new Regex(c.getKey(), "(cfduid|cf_clearance)").matches()) {
                        cookies.put(c.getKey(), c.getValue());
                    }
                }
                synchronized (cloudflareCookies) {
                    cloudflareCookies.clear();
                    cloudflareCookies.putAll(cookies);
                }
            }
        }
        correctBR();
    }

    @SuppressWarnings("unused")
    private void postPage(String page, final String postData) throws Exception {
        if (page == null || postData == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // stable sucks
        if (isJava7nJDStable() && page.startsWith("https")) {
            page = page.replaceFirst("https://", "http://");
        }
        br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        try {
            br.postPage(page, postData);
        } finally {
            br.getHeaders().put("Content-Type", null);
        }
        correctBR();
    }

    private void sendForm(final Form form) throws Exception {
        if (form == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        // stable sucks && lame to the max, lets try and send a form outside of desired protocol. (works with oteupload)
        if (Form.MethodType.POST.equals(form.getMethod())) {
            // if the form doesn't contain an action lets set one based on current br.getURL().
            if (form.getAction() == null || form.getAction().equals("")) {
                form.setAction(br.getURL());
            }
            if (isJava7nJDStable() && (form.getAction().contains("https://") || /* relative path */(!form.getAction().startsWith("http")))) {
                if (!form.getAction().startsWith("http") && br.getURL().contains("https://")) {
                    // change relative path into full path, with protocol correction
                    String basepath = new Regex(br.getURL(), "(https?://.+)/[^/]+$").getMatch(0);
                    String basedomain = new Regex(br.getURL(), "(https?://[^/]+)").getMatch(0);
                    String path = form.getAction();
                    String finalpath = null;
                    if (path.startsWith("/")) {
                        finalpath = basedomain.replaceFirst("https://", "http://") + path;
                    } else if (!path.startsWith(".")) {
                        finalpath = basepath.replaceFirst("https://", "http://") + path;
                    } else {
                        // lacking builder for ../relative paths. this will do for now.
                        logger.info("Missing relative path builder. Must abort now... Try upgrading to JDownloader 2");
                        throw new PluginException(LinkStatus.ERROR_FATAL);
                    }
                    form.setAction(finalpath);
                } else {
                    form.setAction(form.getAction().replaceFirst("https?://", "http://"));
                }
            }
            br.getHeaders().put("Content-Type", "application/x-www-form-urlencoded");
        }
        try {
            br.submitForm(form);
        } finally {
            br.getHeaders().put("Content-Type", null);
        }
        correctBR();
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        setConstants(null);
        requestFileInformation(downloadLink);
        doFree(downloadLink, null);
    }

    /**
     * This fixes filenames from all xfs modules: file hoster, audio/video streaming (including transcoded video), or blocked link checking
     * which is based on fuid.
     *
     * @version 0.2
     * @author raztoki
     */
    private void fixFilename(final DownloadLink downloadLink) {
        String orgName = null;
        String orgExt = null;
        String servName = null;
        String servExt = null;
        String orgNameExt = downloadLink.getFinalFileName();
        if (orgNameExt == null) {
            orgNameExt = downloadLink.getName();
        }
        if (!inValidate(orgNameExt) && orgNameExt.contains(".")) {
            orgExt = orgNameExt.substring(orgNameExt.lastIndexOf("."));
        }
        if (!inValidate(orgExt)) {
            orgName = new Regex(orgNameExt, "(.+)" + orgExt).getMatch(0);
        } else {
            orgName = orgNameExt;
        }
        // if (orgName.endsWith("...")) orgName = orgName.replaceFirst("\\.\\.\\.$", "");
        String servNameExt = Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection()));
        if (!inValidate(servNameExt) && servNameExt.contains(".")) {
            servExt = servNameExt.substring(servNameExt.lastIndexOf("."));
            servName = new Regex(servNameExt, "(.+)" + servExt).getMatch(0);
        } else {
            servName = servNameExt;
        }
        String FFN = null;
        if (orgName.equalsIgnoreCase(fuid.toLowerCase())) {
            FFN = servNameExt;
        } else if (inValidate(orgExt) && !inValidate(servExt) && (servName.toLowerCase().contains(orgName.toLowerCase()) && !servName.equalsIgnoreCase(orgName))) {
            // when partial match of filename exists. eg cut off by quotation mark miss match, or orgNameExt has been abbreviated by hoster.
            FFN = servNameExt;
        } else if (!inValidate(orgExt) && !inValidate(servExt) && !orgExt.equalsIgnoreCase(servExt)) {
            FFN = orgName + servExt;
        } else {
            FFN = orgNameExt;
        }
        downloadLink.setFinalFileName(FFN);
    }

    private String checkDirectLink(final DownloadLink downloadLink) {
        dllink = downloadLink.getStringProperty(directlinkproperty, null);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(directlinkproperty, Property.NULL);
                    dllink = null;
                }
            } catch (Exception e) {
                downloadLink.setProperty(directlinkproperty, Property.NULL);
                dllink = null;
            } finally {
                try {
                    con.disconnect();
                } catch (final Throwable e) {
                }
            }
        }
        return dllink;
    }

    private Form handlePassword(final Form pwform, final DownloadLink downloadLink) throws PluginException {
        if (pwform == null) {
            // so we know handlePassword triggered without any form
            logger.info("Password Form == null");
            return null;
        }
        passCode = downloadLink.getStringProperty("pass", null);
        if (inValidate(passCode)) {
            passCode = Plugin.getUserInput("Password?", downloadLink);
        }
        if (inValidate(passCode)) {
            logger.info("User has entered blank password, exiting handlePassword");
            passCode = null;
            downloadLink.setProperty("pass", Property.NULL);
            return pwform;
        }
        logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
        pwform.put("password", Encoding.urlEncode(passCode));
        return pwform;
    }

    /**
     * captcha processing can be used download/login/anywhere assuming the submit values are the same (they usually are)...
     *
     * @author raztoki
     */
    private Form captchaForm(DownloadLink downloadLink, Form form) throws Exception {
        final int captchaTries = downloadLink.getIntegerProperty("captchaTries", 0);
        if (form.containsHTML(";background:#ccc;text-align")) {
            logger.info("Detected captcha method \"Plaintext Captcha\"");
            /** Captcha method by ManiacMansion */
            String[][] letters = form.getRegex("<span style=\"position:absolute;padding-left:(\\d+)px;padding-top:\\d+px;\">(&#\\d+;)</span>").getMatches();
            if (letters == null || letters.length == 0) {
                letters = cbr.getRegex("<span style='position:absolute;padding-left:(\\d+)px;padding-top:\\d+px;'>(&#\\d+;)</span>").getMatches();
                if (letters == null || letters.length == 0) {
                    logger.warning("plaintext captchahandling broken!");
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
            }
            final SortedMap<Integer, String> capMap = new TreeMap<Integer, String>();
            for (String[] letter : letters) {
                capMap.put(Integer.parseInt(letter[0]), Encoding.htmlDecode(letter[1]));
            }
            final StringBuilder code = new StringBuilder();
            for (String value : capMap.values()) {
                code.append(value);
            }
            form.put("code", code.toString());
        } else if (form.containsHTML("/captchas/")) {
            logger.info("Detected captcha method \"Standard Captcha\"");
            final String[] sitelinks = HTMLParser.getHttpLinks(form.getHtmlCode(), null);
            if (sitelinks == null || sitelinks.length == 0) {
                logger.warning("Standard captcha captchahandling broken!");
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            String code = null;
            for (final String link : sitelinks) {
                if (link.matches("(https?.+" + DOMAINS + ")?/captchas/[a-z0-9]{18,}\\.jpg")) {
                    final Browser testcap = br.cloneBrowser();
                    URLConnectionAdapter con = null;
                    try {
                        con = testcap.openGetConnection(link);
                        if (con.getResponseCode() == 200) {
                            code = getCaptchaCode("xfilesharingprobasic", link, downloadLink);
                            if (!inValidate(code)) {
                                break;
                            }
                        }
                    } catch (final Exception e) {
                        if (e instanceof CaptchaException) {
                            throw e;
                        }
                        continue;
                    }
                }
            }
            if (inValidate(code)) {
                logger.warning("User Aborted");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            form.put("code", Encoding.urlEncode(code));
        } else if (form.containsHTML("(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)")) {
            logger.info("Detected captcha method \"Re Captcha\"");
            final Browser captcha = br.cloneBrowser();
            cleanupBrowser(captcha, form.getHtmlCode());
            final Recaptcha rc = new Recaptcha(captcha, this);
            final String id = form.getRegex("\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
            if (inValidate(id)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            rc.setId(id);
            rc.load();
            final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
            final String c = getCaptchaCode("recaptcha", cf, downloadLink);
            form.put("recaptcha_challenge_field", rc.getChallenge());
            form.put("recaptcha_response_field", Encoding.urlEncode(c));
            /** wait time is often skippable for reCaptcha handling */
            skipWaitTime = waitTimeSkipableReCaptcha;
        } else if (form.containsHTML("solvemedia\\.com/papi/")) {
            logger.info("Detected captcha method \"Solve Media\"");
            final Browser captcha = br.cloneBrowser();
            cleanupBrowser(captcha, form.getHtmlCode());
            final PluginForDecrypt solveplug = JDUtilities.getPluginForDecrypt("linkcrypt.ws");
            final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(captcha);
            final File cf = sm.downloadCaptcha(getLocalCaptchaFile());
            String code = "";
            String chid = sm.getChallenge();
            if (!captchaSkipableSolveMedia || captchaTries > 0) {
                code = getCaptchaCode("solvemedia", cf, downloadLink);
                chid = sm.getChallenge(code);
            }
            form.put("adcopy_challenge", chid);
            form.put("adcopy_response", Encoding.urlEncode(code));
            skipWaitTime = waitTimeSkipableSolveMedia;
        } else if (form.containsHTML("id=\"capcode\" name= \"capcode\"")) {
            logger.info("Detected captcha method \"Key Captcha\"");
            final Browser captcha = br.cloneBrowser();
            cleanupBrowser(captcha, form.getHtmlCode());
            String result = handleCaptchaChallenge(getDownloadLink(), new KeyCaptcha(this, captcha, getDownloadLink()).createChallenge(form.hasInputFieldByName("login") && form.hasInputFieldByName("password"), this));
            if (result == null || "CANCEL".equals(result)) {
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            form.put("capcode", result);
            skipWaitTime = waitTimeSkipableKeyCaptcha;
        }
        downloadLink.setProperty("captchaTries", (captchaTries + 1));
        return form;
    }

    /**
     * @param source
     *            for the Regular Expression match against
     * @return String result
     */
    private String regexDllink(final String source) {
        if (inValidate(source)) {
            return null;
        }
        String result = null;
        // using the following logic to help pick up URL that contains encoded ' character. Very hard to make generic regular expressions at
        // 100% accuracy when using [^\"']+
        String[] links = HTMLParser.getHttpLinks(source, null);
        if (links != null && links.length != 0) {
            for (String link : links) {
                if (link.matches(dllinkRegex) && inValidate(dllink)) {
                    result = link;
                } else if (link.matches(dllinkRegex) && !inValidate(dllink) && link.contains("%27")) {
                    // this picks up url encoded link that contains ' and updates result
                    result = link;
                }
            }
        }
        if (inValidate(result)) {
            result = new Regex(source, "(\"|')(" + dllinkRegex + ")\\1").getMatch(1);
        }
        return result;
    }

    /**
     * @param source
     *            String for decoder to process
     * @return String result
     */
    private void decodeDownloadLink(final String s) {
        String decoded = null;
        try {
            Regex params = new Regex(s, "'(.*?[^\\\\])',(\\d+),(\\d+),'(.*?)'");
            String p = params.getMatch(0).replaceAll("\\\\", "");
            int a = Integer.parseInt(params.getMatch(1));
            int c = Integer.parseInt(params.getMatch(2));
            String[] k = params.getMatch(3).split("\\|");
            while (c != 0) {
                c--;
                if (k[c].length() != 0) {
                    p = p.replaceAll("\\b" + Integer.toString(c, a) + "\\b", k[c]);
                }
            }
            decoded = p;
        } catch (Exception e) {
        }
        if (!inValidate(decoded)) {
            dllink = regexDllink(decoded);
        }
    }

    /**
     * Prevents more than one free download from starting at a given time. One step prior to dl.startDownload(), it adds a slot to maxFree
     * which allows the next singleton download to start, or at least try.
     *
     * This is needed because xfileshare(website) only throws errors after a final dllink starts transferring or at a given step within pre
     * download sequence. But this template(XfileSharingProBasic) allows multiple slots(when available) to commence the download sequence,
     * this.setstartintival does not resolve this issue. Which results in x(20) captcha events all at once and only allows one download to
     * start. This prevents wasting peoples time and effort on captcha solving and|or wasting captcha trading credits. Users will experience
     * minimal harm to downloading as slots are freed up soon as current download begins.
     *
     * @param controlSlot
     *            (+1|-1)
     */
    private boolean              downloadFlag   = false;
    private static AtomicInteger freeSlotsInUse = new AtomicInteger(0);
    private static AtomicInteger premSlotsInUse = new AtomicInteger(0);

    private void controlSlot(final int num, final Account account) {
        synchronized (CTRLLOCK) {
            if (num == 1) {
                if (downloadFlag == false) {
                    if (account == null) {
                        freeSlotsInUse.incrementAndGet();
                    } else {
                        premSlotsInUse.incrementAndGet();
                    }
                    downloadFlag = true;
                } else {
                    return;
                }
            } else {
                if (downloadFlag) {
                    if (account == null) {
                        freeSlotsInUse.decrementAndGet();
                    } else {
                        premSlotsInUse.decrementAndGet();
                    }
                    downloadFlag = false;
                } else {
                    if (account == null) {
                        final int was = maxFree.get();
                        maxFree.set(Math.max(1, was - 1));
                        logger.info("maxFree(Penalty) was=" + was + "|now = " + maxFree.get());
                    } else {
                        final int was = maxPrem.get();
                        maxPrem.set(Math.max(1, was - 1));
                        logger.info("maxPrem(Penalty) was=" + was + "|now = " + maxPrem.get());
                    }
                    return;
                }
            }
            if (account == null) {
                final int was = maxFree.getAndSet(Math.min(freeSlotsInUse.get() + 1, totalMaxSimultanFreeDownload.get()));
                logger.info("maxFree(Slot) was=" + was + "|now = " + maxFree.get());
            } else {
                final int was = maxPrem.getAndSet(Math.min(premSlotsInUse.get() + 1, account.getIntegerProperty("totalMaxSim", 5)));
                logger.info("maxPrem(Slot) was=" + was + "|now = " + maxPrem.get());
            }
        }
    }

    /**
     * ControlSimHost, On error it will set the upper mark for 'max sim dl per host'. This will be the new 'static' setting used going
     * forward. Thus prevents new downloads starting when not possible and is self aware and requires no coder interaction.
     *
     * @param account
     *
     * @category 'Experimental', Mod written February 2013
     */
    private void controlSimHost(final Account account) {
        synchronized (CTRLLOCK) {
            if (usedHost != null) {
                final AtomicInteger value = getHashedHashedValue(account);
                final int was, current;
                if (account != null) {
                    if (account.getBooleanProperty("free", false)) {
                        // free account
                        was = maxFreeAccSimDlPerHost.getAndSet(value.get() - 1);
                        current = maxFreeAccSimDlPerHost.get();
                    } else {
                        // premium account
                        was = maxPremAccSimDlPerHost.getAndSet(value.get() - 1);
                        current = maxPremAccSimDlPerHost.get();
                    }
                    logger.info("maxSimPerHost = " + account.getUser() + " @ " + acctype + " -> was = " + was + " && new upper limit = " + current);
                } else {
                    // non account
                    was = maxNonAccSimDlPerHost.getAndSet(value.get() - 1);
                    current = maxNonAccSimDlPerHost.get();
                    logger.info("maxSimPerHost = Guest @ " + acctype + " -> was = " + was + " && new upper limit = " + current);
                }
            }
        }
    }

    /**
     * This matches dllink against an array of used 'host' servers. Use this when site have multiple download servers and they allow x
     * connections to ip/host server. Currently JD allows a global connection controller and doesn't allow for handling of different
     * hosts/IP setup. This will help with those situations by allowing more connection when possible.
     *
     * @param Account
     *            Account that's been used, can be null
     * @param DownloadLink
     * @param action
     *            To add or remove slot, true == adds, false == removes
     * @throws Exception
     */
    private void controlHost(final Account account, final DownloadLink downloadLink, final boolean action) throws Exception {
        synchronized (CTRLLOCK) {
            // xfileshare valid links are either https://((sub.)?domain|IP)(:port)?/blah
            usedHost = new Regex(dllink, "https?://([^/\\:]+)").getMatch(0);
            if (inValidate(dllink) || usedHost == null) {
                if (inValidate(dllink)) {
                    logger.warning("Invalid URL given to controlHost");
                } else {
                    logger.warning("Regex on usedHost failed, Please report this to JDownloader Development Team");
                }
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            // save finallink and use it for later, this script can determine if it's usable at a later stage. (more for dev purposes)
            downloadLink.setProperty(directlinkproperty, dllink);
            // place account into a place holder, for later references;
            Account accHolder = account;
            // allows concurrent logic
            boolean thisAccount = allowsConcurrent(account);
            boolean continu = true;
            if (!hostMap.isEmpty()) {
                // compare stored values within hashmap, determine if they allow concurrent with current account download request!
                for (Entry<Account, HashMap<String, AtomicInteger>> holder : hostMap.entrySet()) {
                    if (!allowsConcurrent(holder.getKey())) {
                        continu = false;
                    }
                }
                if (thisAccount && continu) {
                    // current account allows concurrent
                    // hostmap entries c
                }
            }
            String user = null;
            Integer simHost;
            if (accHolder != null) {
                user = accHolder.getUser();
                if (accHolder.getBooleanProperty("free")) {
                    // free account
                    simHost = maxFreeAccSimDlPerHost.get();
                } else {
                    // normal account
                    simHost = maxPremAccSimDlPerHost.get();
                }
            } else {
                user = "Guest";
                simHost = maxNonAccSimDlPerHost.get();
            }
            user = user + " @ " + acctype;
            if (!action) {
                // download finished (completed, failed, etc), check for value and remove a value
                // Improvised workaround for fatal XFS3 bug
                if (!containsHashedHashedKey(account, usedHost)) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final int x = decrementAndGetHashedHashKeyValue(account);
                if (x == 0) {
                    logger.info("controlHost = " + user + " -> " + usedHost + " :: No longer used!");
                } else {
                    logger.info("controlHost = " + user + " -> " + usedHost + " :: " + x + " simulatious connection(s)");
                }
            } else {
                // New download started, check finallink host against hostMap values && max(Free|Prem)SimDlHost!
                /*
                 * max(Free|Prem)SimDlHost prevents more downloads from starting on a given host! At least until one of the previous
                 * downloads finishes. This is best practice otherwise you have to use some crude system of waits, but you have no control
                 * over to reset the count. Highly dependent on how fast or slow the users connections is.
                 */
                if (containsHashedHashedKey(account, usedHost)) {
                    final AtomicInteger usedSlots = getHashedHashedValue(account);
                    if (usedSlots == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    if (!usedSlots.equals(simHost)) {
                        final int x = incrementAndGetHashedHashKeyValue(account);
                        logger.info("controlHost = " + user + " -> " + usedHost + " :: " + x + " simulatious connection(s)");
                    } else {
                        logger.info("controlHost = " + user + " -> " + usedHost + " :: Too many concurrent connectons. We will try again when next possible.");
                        controlSlot(-1, accHolder);
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Too many concurrent connectons. We will try again when next possible.", 10 * 1000);
                    }
                } else {
                    // virgin download for given usedHost.
                    final int x = incrementAndGetHashedHashKeyValue(account);
                    logger.info("controlHost = " + user + " -> " + usedHost + " :: " + x + " simulatious connection(s)");
                }
            }
        }
    }

    private int incrementAndGetHashedHashKeyValue(final Account account) throws PluginException {
        return setHashedHashKeyValue(account, +1);
    }

    private int decrementAndGetHashedHashKeyValue(final Account account) throws PluginException {
        return setHashedHashKeyValue(account, -1);
    }

    /**
     * Sets Key and Values to respective Account stored within hostMap
     *
     * @param account
     *            Account that's been used, can be null
     * @param x
     *            Integer positive or negative. Positive adds slots. Negative integer removes slots.
     * @throws PluginException
     */
    private int setHashedHashKeyValue(final Account account, final int delta) throws PluginException {
        synchronized (CTRLLOCK) {
            if (usedHost != null) {
                boolean addHolder = true;
                HashMap<String, AtomicInteger> holder = new HashMap<String, AtomicInteger>();
                if (!hostMap.isEmpty() && hostMap.containsKey(account)) {
                    addHolder = false;
                    // load hostMap within holder if not empty
                    holder = hostMap.get(account);
                    if (holder == null) {
                        // this shouldn't happen
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                }
                AtomicInteger atomicValue = getHashedHashedValue(account);
                if (atomicValue == null) {
                    atomicValue = new AtomicInteger(0);
                    holder.put(usedHost, atomicValue);
                }
                final int value = atomicValue.addAndGet(delta);
                if (value == 0) {
                    holder.remove(usedHost);
                } else if (value < 0) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                if (holder.isEmpty()) {
                    // the last value(download) within holder->account. Remove entry to reduce memory allocation
                    if (addHolder == false) {
                        hostMap.remove(account);
                    }
                } else if (addHolder) {
                    // put updated holder back into hostMap
                    hostMap.put(account, holder);
                }
                return value;
            }
        }
        return 0;
    }

    /**
     * Returns integer value from Account@usedHost from hostMap
     *
     * @param account
     *            Account that's been used, can be null
     */
    private AtomicInteger getHashedHashedValue(final Account account) {
        synchronized (CTRLLOCK) {
            if (usedHost != null) {
                final HashMap<String, AtomicInteger> accKeyValue = hostMap.get(account);
                if (accKeyValue != null) {
                    return accKeyValue.get(usedHost);
                }
            }
            return null;
        }
    }

    /**
     * Returns true if hostMap contains 'key'
     *
     * @param account
     *            Account that's been used, can be null
     * @param key
     *            String of what ever you want to find
     */
    private boolean containsHashedHashedKey(final Account account, final String key) {
        synchronized (CTRLLOCK) {
            if (key != null) {
                final HashMap<String, AtomicInteger> accKeyValue = hostMap.get(account);
                return accKeyValue != null && accKeyValue.containsKey(key);
            }
            return false;
        }
    }

    /**
     * Validates string to series of conditions, null, whitespace, or "". This saves effort factor within if/for/while statements
     *
     * @param s
     *            Imported String to match against.
     * @return <b>true</b> on valid rule match. <b>false</b> on invalid rule match.
     * @author raztoki
     */
    private boolean inValidate(final String s) {
        if (s == null || (s.matches("[\r\n\t ]+") || s.equals(""))) {
            return true;
        } else {
            return false;
        }
    }

    // TODO: remove this when v2 becomes stable. use br.getFormbyKey(String key, String value)
    /**
     * Returns the first form that has a 'key' that equals 'value'.
     *
     * @param key
     *            name
     * @param value
     *            expected value
     * @param ibr
     *            import browser
     */
    private Form getFormByKey(final Browser ibr, final String key, final String value) {
        Form[] workaround = ibr.getForms();
        if (workaround != null) {
            for (Form f : workaround) {
                for (InputField field : f.getInputFields()) {
                    if (key != null && key.equals(field.getKey())) {
                        if (value == null && field.getValue() == null) {
                            return f;
                        }
                        if (value != null && value.equals(field.getValue())) {
                            return f;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * If form contain both " and ' quotation marks within input fields it can return null values, thus you submit wrong/incorrect data re:
     * InputField parse(final String data). Affects revision 19688 and earlier!
     *
     * TODO: remove after JD2 goes stable!
     *
     * @author raztoki
     */
    private Form cleanForm(Form form) {
        if (form == null) {
            return null;
        }
        String data = form.getHtmlCode();
        ArrayList<String> cleanupRegex = new ArrayList<String>();
        cleanupRegex.add("(\\w+\\s*=\\s*\"[^\"]+\")");
        cleanupRegex.add("(\\w+\\s*=\\s*'[^']+')");
        for (String reg : cleanupRegex) {
            String results[] = new Regex(data, reg).getColumn(0);
            if (results != null) {
                String quote = new Regex(reg, "(\"|')").getMatch(0);
                for (String result : results) {
                    String cleanedResult = result.replaceFirst(quote, "\\\"").replaceFirst(quote + "$", "\\\"");
                    data = data.replace(result, cleanedResult);
                }
            }
        }
        Form ret = new Form(data);
        ret.setAction(form.getAction());
        ret.setMethod(form.getMethod());
        return ret;
    }

    /**
     * This allows backward compatibility for design flaw in setHtmlCode(), It injects updated html into all browsers that share the same
     * request id. This is needed as request.cloneRequest() was never fully implemented like browser.cloneBrowser().
     *
     * @param ibr
     *            Import Browser
     * @param t
     *            Provided replacement string output browser
     * @author raztoki
     */
    private void cleanupBrowser(final Browser ibr, final String t) throws Exception {
        String dMD5 = JDHash.getMD5(ibr.toString());
        // preserve valuable original request components.
        final String oURL = ibr.getURL();
        final URLConnectionAdapter con = ibr.getRequest().getHttpConnection();
        Request req = new Request(oURL) {
            {
                boolean okay = false;
                try {
                    final Field field = this.getClass().getSuperclass().getDeclaredField("requested");
                    field.setAccessible(true);
                    field.setBoolean(this, true);
                    okay = true;
                } catch (final Throwable e2) {
                    e2.printStackTrace();
                }
                if (okay == false) {
                    try {
                        requested = true;
                    } catch (final Throwable e) {
                        e.printStackTrace();
                    }
                }
                httpConnection = con;
                setHtmlCode(t);
            }

            public long postRequest() throws IOException {
                return 0;
            }

            public void preRequest() throws IOException {
            }
        };
        ibr.setRequest(req);
        if (ibr.isDebug()) {
            logger.info("\r\ndirtyMD5sum = " + dMD5 + "\r\ncleanMD5sum = " + JDHash.getMD5(ibr.toString()) + "\r\n");
            System.out.println(ibr.toString());
        }
    }

    private AvailableStatus getAvailableStatus(DownloadLink link) {
        try {
            final Field field = link.getClass().getDeclaredField("availableStatus");
            field.setAccessible(true);
            Object ret = field.get(link);
            if (ret != null && ret instanceof AvailableStatus) {
                return (AvailableStatus) ret;
            }
        } catch (final Throwable e) {
        }
        return AvailableStatus.UNCHECKED;
    }

    private boolean isJava7nJDStable() {
        if (System.getProperty("jd.revision.jdownloaderrevision") == null && System.getProperty("java.version").matches("1\\.[7-9].+")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * because stable is lame!
     */
    public void setBrowser(final Browser ibr) {
        this.br = ibr;
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.SibSoft_XFileShare;
    }
}