//jDownloader - Downloadmanager
//Copyright (C) 2012  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v1.Recaptcha;
import org.jdownloader.plugins.components.antiDDoSForHost;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.parser.html.InputField;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForDecrypt;
import jd.plugins.components.SiteType.SiteTemplate;
import jd.utils.JDUtilities;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "daclips.in" }, urls = { "https?://(?:www\\.)?daclips\\.(?:in|com)/[a-z0-9]{12}" })
public class DaClipsIn extends antiDDoSForHost {
    private String               correctedBR                  = "";
    private static final String  PASSWORDTEXT                 = "<br><b>Passwor(d|t):</b> <input";
    private final String         COOKIE_HOST                  = "http://daclips.in";
    private static final String  MAINTENANCE                  = ">This server is in maintenance mode";
    private static final String  MAINTENANCEUSERTEXT          = JDL.L("hoster.xfilesharingprobasic.errors.undermaintenance", "This server is under Maintenance");
    private static final String  ALLWAIT_SHORT                = JDL.L("hoster.xfilesharingprobasic.errors.waitingfordownloads", "Waiting till new downloads can be started");
    private static final String  PREMIUMONLY1                 = JDL.L("hoster.xfilesharingprobasic.errors.premiumonly1", "Max downloadable filesize for free users:");
    private static final String  PREMIUMONLY2                 = JDL.L("hoster.xfilesharingprobasic.errors.premiumonly2", "Only downloadable via premium or registered");
    private static final boolean VIDEOHOSTER                  = false;
    // note: can not be negative -x or 0 .:. [1-*]
    private static AtomicInteger totalMaxSimultanFreeDownload = new AtomicInteger(1);
    // don't touch
    private static AtomicInteger maxFree                      = new AtomicInteger(1);

    // DEV NOTES
    // XfileSharingProBasic Version 2.5.8.9
    // mods: getdllink
    // non account: 2 * 1
    // free account: chunk * maxdl
    // premium account: chunk * maxdl
    // protocol: no https
    // captchatype: null
    // other: sister sites, movpod.in && gorillavid.in
    @Override
    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replace("https://", "http://").replace("daclips.com/", "daclips.in/"));
    }

    @Override
    public String getAGBLink() {
        return COOKIE_HOST + "/tos.html";
    }

    public DaClipsIn(PluginWrapper wrapper) {
        super(wrapper);
        // this.enablePremium(COOKIE_HOST + "/premium.html");
    }

    // do not add @Override here to keep 0.* compatibility
    public boolean hasAutoCaptcha() {
        return true;
    }

    public void prepBrowser(final Browser br) {
        // define custom browser headers and language settings.
        JDUtilities.getPluginForHost("mediafire.com");
        br.getHeaders().put("User-Agent", jd.plugins.hoster.MediafireCom.stringUserAgent());
        br.getHeaders().put("Accept-Language", "en-gb, en;q=0.9, de;q=0.8");
        br.setCookie(COOKIE_HOST, "lang", "english");
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        br.setFollowRedirects(true);
        prepBrowser(br);
        getPage(link.getDownloadURL());
        if (new Regex(correctedBR, "(No such file|>(404 - )?File Not Found<|>The file was removed by|Reason for deletion:\n)").matches()) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (new Regex(correctedBR, MAINTENANCE).matches()) {
            link.getLinkStatus().setStatusText(MAINTENANCEUSERTEXT);
            return AvailableStatus.TRUE;
        }
        if (br.getURL().contains("/?op=login&redirect=")) {
            link.getLinkStatus().setStatusText(PREMIUMONLY2);
            return AvailableStatus.UNCHECKABLE;
        }
        String[] fileInfo = new String[3];
        // scan the first page
        scanInfo(fileInfo);
        // scan the second page. filesize[1] and md5hash[2] are not mission
        // critical
        if (fileInfo[0] == null) {
            Form download1 = getFormByKey("op", "download1");
            if (download1 != null) {
                download1.remove("method_premium");
                waitTime(System.currentTimeMillis(), link);
                sendForm(download1);
                scanInfo(fileInfo);
            }
        }
        if (fileInfo[0] == null || fileInfo[0].equals("")) {
            if (correctedBR.contains("You have reached the download(\\-| )limit")) {
                logger.warning("Waittime detected, please reconnect to make the linkchecker work!");
                return AvailableStatus.UNCHECKABLE;
            }
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (fileInfo[2] != null && !fileInfo[2].equals("")) {
            link.setMD5Hash(fileInfo[2].trim());
        }
        fileInfo[0] = fileInfo[0].replaceAll("(</b>|<b>|\\.html)", "");
        link.setFinalFileName(fileInfo[0].trim());
        if (fileInfo[1] != null && !fileInfo[1].equals("")) {
            link.setDownloadSize(SizeFormatter.getSize(fileInfo[1]));
        }
        Form download1 = getFormByKey("op", "download1");
        if (download1 != null) {
            download1.remove("method_premium");
            // this.waitTime(System.currentTimeMillis(), downloadLink);
            sendForm(download1);
            // checkErrors(downloadLink, false, passCode);
        }
        /** 2018-08-31: Removed filesizecheck as they often have offline URLs which then results in a very long availablecheck! */
        // String dllink = getDllink(br);
        // if (dllink != null) {
        // final Browser br2 = br.cloneBrowser();
        // br2.setFollowRedirects(true);
        // URLConnectionAdapter con = null;
        // try {
        // con = br2.openHeadConnection(dllink);
        // if (!con.getContentType().contains("html")) {
        // if (con.getLongContentLength() < 1000) {
        // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        // }
        // link.setDownloadSize(con.getLongContentLength());
        // link.setProperty("freelink", dllink);
        // } else {
        // logger.info("dllink error");
        // throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        // }
        // } finally {
        // try {
        // con.disconnect();
        // } catch (final Throwable e) {
        // }
        // }
        // }
        return AvailableStatus.TRUE;
    }

    private String[] scanInfo(final String[] fileInfo) {
        // standard traits from base page
        if (fileInfo[0] == null) {
            fileInfo[0] = new Regex(correctedBR, "You have requested.*?https?://(www\\.)?" + this.getHost() + "/[A-Za-z0-9]{12}/(.*?)</font>").getMatch(1);
            if (fileInfo[0] == null) {
                fileInfo[0] = new Regex(correctedBR, "fname\"( type=\"hidden\")? value=\"(.*?)\"").getMatch(1);
                if (fileInfo[0] == null) {
                    fileInfo[0] = new Regex(correctedBR, "<h2>Download File(.*?)</h2>").getMatch(0);
                    // traits from download1 page below.
                    if (fileInfo[0] == null) {
                        fileInfo[0] = new Regex(correctedBR, "Filename:? ?(<[^>]+> ?)+?([^<>\"\\']+)").getMatch(1);
                        // next two are details from sharing box
                        if (fileInfo[0] == null) {
                            fileInfo[0] = new Regex(correctedBR, "copy\\(this\\);.+>(.+) \\- [\\d\\.]+ (KB|MB|GB)</a></textarea>[\r\n\t ]+</div>").getMatch(0);
                            if (fileInfo[0] == null) {
                                fileInfo[0] = new Regex(correctedBR, "copy\\(this\\);.+\\](.+) \\- [\\d\\.]+ (KB|MB|GB)\\[/URL\\]").getMatch(0);
                            }
                        }
                    }
                }
            }
        }
        if (fileInfo[1] == null) {
            fileInfo[1] = new Regex(correctedBR, "\\(([0-9]+ bytes)\\)").getMatch(0);
            if (fileInfo[1] == null) {
                fileInfo[1] = new Regex(correctedBR, "</font>[ ]+\\(([^<>\"\\'/]+)\\)(.*?)</font>").getMatch(0);
                if (fileInfo[1] == null) {
                    // fileInfo[1] = new Regex(correctedBR, "(\\d+(\\.\\d+)? ?(KB|MB|GB))").getMatch(0);
                }
            }
        }
        if (fileInfo[2] == null) {
            fileInfo[2] = new Regex(correctedBR, "<b>MD5.*?</b>.*?nowrap>(.*?)<").getMatch(0);
        }
        return fileInfo;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception, PluginException {
        requestFileInformation(downloadLink);
        doFree(downloadLink, true, -2, "freelink");
    }

    @SuppressWarnings("unused")
    public void doFree(final DownloadLink downloadLink, final boolean resumable, final int maxchunks, final String directlinkproperty) throws Exception, PluginException {
        br.setFollowRedirects(false);
        String passCode = null;
        // First, bring up saved final links
        String dllink = checkDirectLink(downloadLink, directlinkproperty);
        // Second, check for streaming links on the first page
        if (dllink == null) {
            dllink = getDllink(br);
        }
        // Try to get stream link
        if (dllink == null && VIDEOHOSTER) {
            final Browser brv = br.cloneBrowser();
            super.getPage(brv, COOKIE_HOST + "/vidembed-" + new Regex(downloadLink.getDownloadURL(), "([a-z0-9]+)$").getMatch(0));
            dllink = getDllink(brv);
        }
        // Third, continue like normal.
        if (dllink == null) {
            getPage(downloadLink.getDownloadURL());
            checkErrors(downloadLink, false, passCode);
            Form download1 = getFormByKey("op", "download1");
            if (download1 != null) {
                download1.remove("method_premium");
                this.waitTime(System.currentTimeMillis(), downloadLink);
                sendForm(download1);
                checkErrors(downloadLink, false, passCode);
            }
            dllink = getDllink(br);
        }
        if (dllink == null) {
            Form dlForm = br.getFormbyProperty("name", "F1");
            if (dlForm == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            // how many forms deep do you want to try.
            int repeat = 3;
            for (int i = 1; i <= repeat; i++) {
                dlForm.remove(null);
                final long timeBefore = System.currentTimeMillis();
                boolean password = false;
                boolean skipWaittime = false;
                if (new Regex(correctedBR, PASSWORDTEXT).matches()) {
                    password = true;
                    logger.info("The downloadlink seems to be password protected.");
                }
                // md5 can be on the subquent pages
                if (downloadLink.getMD5Hash() == null) {
                    String md5hash = new Regex(correctedBR, "<b>MD5.*?</b>.*?nowrap>(.*?)<").getMatch(0);
                    if (md5hash != null) {
                        downloadLink.setMD5Hash(md5hash.trim());
                    }
                }
                /* Captcha START */
                if (correctedBR.contains(";background:#ccc;text-align")) {
                    logger.info("Detected captcha method \"plaintext captchas\" for this host");
                    /** Captcha method by ManiacMansion */
                    final String[][] letters = new Regex(br, "<span style=\\'position:absolute;padding\\-left:(\\d+)px;padding\\-top:\\d+px;\\'>(&#\\d+;)</span>").getMatches();
                    if (letters == null || letters.length == 0) {
                        logger.warning("plaintext captchahandling broken!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    final SortedMap<Integer, String> capMap = new TreeMap<Integer, String>();
                    for (String[] letter : letters) {
                        capMap.put(Integer.parseInt(letter[0]), Encoding.htmlDecode(letter[1]));
                    }
                    final StringBuilder code = new StringBuilder();
                    for (String value : capMap.values()) {
                        code.append(value);
                    }
                    dlForm.put("code", code.toString());
                    logger.info("Put captchacode " + code.toString() + " obtained by captcha metod \"plaintext captchas\" in the form.");
                } else if (correctedBR.contains("/captchas/")) {
                    logger.info("Detected captcha method \"Standard captcha\" for this host");
                    final String[] sitelinks = HTMLParser.getHttpLinks(br.toString(), null);
                    String captchaurl = null;
                    if (sitelinks == null || sitelinks.length == 0) {
                        logger.warning("Standard captcha captchahandling broken!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    for (String link : sitelinks) {
                        if (link.contains("/captchas/")) {
                            captchaurl = link;
                            break;
                        }
                    }
                    if (captchaurl == null) {
                        logger.warning("Standard captcha captchahandling broken!");
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    String code = getCaptchaCode("xfilesharingprobasic", captchaurl, downloadLink);
                    dlForm.put("code", code);
                    logger.info("Put captchacode " + code + " obtained by captcha metod \"Standard captcha\" in the form.");
                } else if (new Regex(correctedBR, "(api\\.recaptcha\\.net|google\\.com/recaptcha/api/)").matches()) {
                    logger.info("Detected captcha method \"Re Captcha\" for this host");
                    final Recaptcha rc = new Recaptcha(br, this);
                    final String id = new Regex(correctedBR, "\\?k=([A-Za-z0-9%_\\+\\- ]+)\"").getMatch(0);
                    if (id == null) {
                        throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                    }
                    rc.setId(id);
                    rc.load();
                    final File cf = rc.downloadCaptcha(getLocalCaptchaFile());
                    final String c = getCaptchaCode("recaptcha", cf, downloadLink);
                    dlForm.put("recaptcha_challenge_field", rc.getChallenge());
                    dlForm.put("recaptcha_response_field", Encoding.urlEncode(c));
                    logger.info("Put captchacode " + c + " obtained by captcha metod \"Re Captcha\" in the form and submitted it.");
                    /** wait time is often skippable for reCaptcha handling */
                    skipWaittime = true;
                } else if (br.containsHTML("//api\\.solvemedia\\.com/papi")) {
                    logger.info("Detected captcha method \"solvemedia\" for this host");
                    final PluginForDecrypt solveplug = JDUtilities.getPluginForDecrypt("linkcrypt.ws");
                    final org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia sm = new org.jdownloader.captcha.v2.challenge.solvemedia.SolveMedia(br);
                    final File cf = sm.downloadCaptcha(getLocalCaptchaFile());
                    final String code = getCaptchaCode("solvemedia", cf, downloadLink);
                    final String chid = sm.getChallenge(code);
                    dlForm.put("adcopy_challenge", chid);
                    dlForm.put("adcopy_response", "manual_challenge");
                }
                /* Captcha END */
                if (password) {
                    passCode = handlePassword(passCode, dlForm, downloadLink);
                }
                if (!skipWaittime) {
                    waitTime(timeBefore, downloadLink);
                }
                sendForm(dlForm);
                logger.info("Submitted DLForm");
                dllink = getDllink(br);
                if (dllink == null && (!br.containsHTML("<Form name=\"F1\" method=\"POST\" action=\"\"") || i == repeat)) {
                    checkErrors(downloadLink, true, passCode);
                    logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
                    break;
                } else if (dllink == null && br.containsHTML("<Form name=\"F1\" method=\"POST\" action=\"\"")) {
                    dlForm = br.getFormbyProperty("name", "F1");
                    continue;
                } else {
                    break;
                }
            }
        }
        checkErrors(downloadLink, true, passCode);
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        logger.info("Final downloadlink = " + dllink + " starting the download...");
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, resumable, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            correctBR();
            checkServerErrors();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setProperty(directlinkproperty, dllink);
        if (passCode != null) {
            downloadLink.setProperty("pass", passCode);
        }
        fixFilename(downloadLink);
        try {
            // add a download slot
            controlFree(+1);
            // start the dl
            dl.startDownload();
        } finally {
            // remove download slot
            controlFree(-1);
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return maxFree.get();
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
     * @param controlFree
     *            (+1|-1)
     */
    public synchronized void controlFree(final int num) {
        logger.info("maxFree was = " + maxFree.get());
        maxFree.set(Math.min(Math.max(1, maxFree.addAndGet(num)), totalMaxSimultanFreeDownload.get()));
        logger.info("maxFree now = " + maxFree.get());
    }

    /** Remove HTML code which could break the plugin */
    public void correctBR() throws NumberFormatException, PluginException {
        correctedBR = br.toString();
        ArrayList<String> someStuff = new ArrayList<String>();
        ArrayList<String> regexStuff = new ArrayList<String>();
        regexStuff.add("<\\!(\\-\\-.*?\\-\\-)>");
        regexStuff.add("(display: ?none;\">.*?</div>)");
        regexStuff.add("(visibility:hidden>.*?<)");
        for (String aRegex : regexStuff) {
            String lolz[] = br.getRegex(aRegex).getColumn(0);
            if (lolz != null) {
                for (String dingdang : lolz) {
                    someStuff.add(dingdang);
                }
            }
        }
        for (String fun : someStuff) {
            correctedBR = correctedBR.replace(fun, "");
        }
    }

    public String getDllink(Browser brg) {
        if (brg == null) {
            brg = br;
        }
        String dllink = brg.getRedirectLocation();
        if (dllink == null) {
            dllink = new Regex(brg, "(?:file|src):\\s*(?:'|\")([^<>'\"]*?)(?:'|\")").getMatch(0);
            if (dllink == null) {
                dllink = new Regex(brg, "(https?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?/(\\d+/)?[a-z0-9]{15,}/[^<>\"]*?)('|\")").getMatch(0);
            }
        }
        return dllink;
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            try {
                Browser br2 = br.cloneBrowser();
                URLConnectionAdapter con = br2.openGetConnection(dllink);
                if (con.getContentType().contains("html") || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
                con.disconnect();
            } catch (Exception e) {
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            }
        }
        return dllink;
    }

    protected void getPage(final String page) throws Exception {
        super.getPage(page);
        correctBR();
    }

    protected void postPage(final String page, final String postdata) throws Exception {
        super.postPage(page, postdata);
        correctBR();
    }

    private void sendForm(final Form form) throws Exception {
        br.submitForm(form);
        correctBR();
    }

    private void waitTime(long timeBefore, final DownloadLink downloadLink) throws PluginException {
        int passedTime = (int) ((System.currentTimeMillis() - timeBefore) / 1000) - 1;
        /** Ticket Time */
        final String ttt = new Regex(correctedBR, ">Wait <span id=\"[a-z0-9]+\">(\\d+)</span> seconds<").getMatch(0);
        if (ttt != null) {
            int tt = Integer.parseInt(ttt);
            tt -= passedTime;
            logger.info("Waittime detected, waiting " + ttt + " - " + passedTime + " seconds from now on...");
            if (tt > 0) {
                sleep(tt * 1000l, downloadLink);
            }
        }
    }

    // TODO: remove this when v2 becomes stable. use br.getFormbyKey(String key,
    // String value)
    /**
     * Returns the first form that has a 'key' that equals 'value'.
     *
     * @param key
     * @param value
     * @return
     */
    private Form getFormByKey(final String key, final String value) {
        Form[] workaround = br.getForms();
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

    private void fixFilename(final DownloadLink downloadLink) {
        final String serverFilename = Encoding.htmlDecode(getFileNameFromHeader(dl.getConnection()));
        final String newExtension = serverFilename.substring(serverFilename.lastIndexOf("."));
        if (newExtension != null && !downloadLink.getFinalFileName().endsWith(newExtension)) {
            final String oldExtension = downloadLink.getFinalFileName().substring(downloadLink.getFinalFileName().lastIndexOf("."));
            if (oldExtension != null) {
                downloadLink.setFinalFileName(downloadLink.getFinalFileName().replace(oldExtension, newExtension));
            } else {
                downloadLink.setFinalFileName(downloadLink.getFinalFileName() + newExtension);
            }
        }
    }

    private String handlePassword(String passCode, final Form pwform, final DownloadLink thelink) throws IOException, PluginException {
        passCode = thelink.getStringProperty("pass", null);
        if (passCode == null) {
            passCode = Plugin.getUserInput("Password?", thelink);
        }
        pwform.put("password", passCode);
        logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
        return Encoding.urlEncode(passCode);
    }

    public void checkErrors(final DownloadLink theLink, final boolean checkAll, final String passCode) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (new Regex(correctedBR, PASSWORDTEXT).matches() || correctedBR.contains("Wrong password")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            if (correctedBR.contains("Wrong captcha")) {
                logger.warning("Wrong captcha or wrong password!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
            if (correctedBR.contains("\">Skipped countdown<")) {
                throw new PluginException(LinkStatus.ERROR_FATAL, "Fatal countdown error (countdown skipped)");
            }
        }
        /** Wait time reconnect handling */
        if (new Regex(correctedBR, "(You have reached the download(\\-| )limit|You have to wait)").matches()) {
            // adjust this regex to catch the wait time string for COOKIE_HOST
            String WAIT = new Regex(correctedBR, "((You have reached the download(\\-| )limit|You have to wait)[^<>]+)").getMatch(0);
            String tmphrs = new Regex(WAIT, "\\s+(\\d+)\\s+hours?").getMatch(0);
            if (tmphrs == null) {
                tmphrs = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            }
            String tmpmin = new Regex(WAIT, "\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (tmpmin == null) {
                tmpmin = new Regex(correctedBR, "You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            }
            String tmpsec = new Regex(WAIT, "\\s+(\\d+)\\s+seconds?").getMatch(0);
            String tmpdays = new Regex(WAIT, "\\s+(\\d+)\\s+days?").getMatch(0);
            if (tmphrs == null && tmpmin == null && tmpsec == null && tmpdays == null) {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                int minutes = 0, seconds = 0, hours = 0, days = 0;
                if (tmphrs != null) {
                    hours = Integer.parseInt(tmphrs);
                }
                if (tmpmin != null) {
                    minutes = Integer.parseInt(tmpmin);
                }
                if (tmpsec != null) {
                    seconds = Integer.parseInt(tmpsec);
                }
                if (tmpdays != null) {
                    days = Integer.parseInt(tmpdays);
                }
                int waittime = ((days * 24 * 3600) + (3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                /** Not enough wait time to reconnect->Wait and try again */
                if (waittime < 180000) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, JDL.L("plugins.hoster.xfilesharingprobasic.allwait", ALLWAIT_SHORT), waittime);
                }
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (correctedBR.contains("You're using all download slots for IP")) {
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (correctedBR.contains("Error happened when generating Download Link")) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l);
        }
        /** Error handling for only-premium links */
        if (new Regex(correctedBR, "( can download files up to |Upgrade your account to download bigger files|>Upgrade your account to download larger files|>The file you requested reached max downloads limit for Free Users|Please Buy Premium To download this file<|This file reached max downloads limit)").matches()) {
            String filesizelimit = new Regex(correctedBR, "You can download files up to(.*?)only").getMatch(0);
            if (filesizelimit != null) {
                filesizelimit = filesizelimit.trim();
                logger.info("As free user you can download files up to " + filesizelimit + " only");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, PREMIUMONLY1 + " " + filesizelimit);
            } else {
                logger.info("Only downloadable via premium");
                try {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
                } catch (final Throwable e) {
                    if (e instanceof PluginException) {
                        throw (PluginException) e;
                    }
                }
                throw new PluginException(LinkStatus.ERROR_FATAL, PREMIUMONLY2);
            }
        }
        if (br.getURL().contains("/?op=login&redirect=")) {
            logger.info("Only downloadable via premium");
            throw new PluginException(LinkStatus.ERROR_FATAL, PREMIUMONLY2);
        }
        if (new Regex(correctedBR, MAINTENANCE).matches()) {
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, MAINTENANCEUSERTEXT, 2 * 60 * 60 * 1000l);
        }
    }

    public void checkServerErrors() throws NumberFormatException, PluginException {
        if (new Regex(correctedBR, Pattern.compile("No file", Pattern.CASE_INSENSITIVE)).matches()) {
            throw new PluginException(LinkStatus.ERROR_FATAL, "Server error");
        }
        if (new Regex(correctedBR, "(File Not Found|<h1>404 Not Found</h1>)").matches()) {
            logger.warning("Server says link offline, please recheck that!");
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    /* NO OVERRIDE!! We need to stay 0.9*compatible */
    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
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

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.SibSoft_XFileShare;
    }

    @Override
    public String[] siteSupportedNames() {
        return new String[] { "daclips.in", "daclips.com" };
    }
}