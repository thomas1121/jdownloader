//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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

import java.io.IOException;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "eroprofile.com" }, urls = { "https?://(www\\.)?eroprofile\\.com/m/(videos|photos)/view/[A-Za-z0-9\\-_]+" })
public class EroProfileCom extends PluginForHost {
    public EroProfileCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium();
    }

    /* DEV NOTES */
    /* Porn_plugin */
    private String dllink = null;

    @Override
    public String getAGBLink() {
        return "http://www.eroprofile.com/p/help/termsOfUse";
    }

    private static final String VIDEOLINK   = "(?i)https?://(www\\.)?eroprofile\\.com/m/videos/view/[A-Za-z0-9\\-_]+";
    private static Object       LOCK        = new Object();
    private static final String MAINPAGE    = "https://eroprofile.com";
    public static final String  NOACCESS    = "(>You do not have the required privileges to view this page|>No access<)";
    private static final String PREMIUMONLY = "The video could not be processed";

    @SuppressWarnings("deprecation")
    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws IOException, PluginException {
        br.setFollowRedirects(true);
        br.setReadTimeout(3 * 60 * 1000);
        br.setCookie("http://eroprofile.com/", "lang", "en");
        br.getPage(downloadLink.getDownloadURL());
        if (br.containsHTML(NOACCESS)) {
            downloadLink.getLinkStatus().setStatusText("Only available for registered users");
            return AvailableStatus.TRUE;
        }
        if (downloadLink.getDownloadURL().matches(VIDEOLINK)) {
            if (br.containsHTML("(>Video not found|>The video could not be found|<title>EroProfile</title>)")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = getFilename();
            if (br.containsHTML(PREMIUMONLY)) {
                downloadLink.setName(filename + ".m4v");
                downloadLink.getLinkStatus().setStatusText("This file is only available to premium members");
                return AvailableStatus.TRUE;
            }
            dllink = br.getRegex("file:\\'(https?://[^<>\"]*?)\\'").getMatch(0);
            if (dllink == null) {
                dllink = br.getRegex("<source src=(?:'|\")(https?[^<>\"]*?)/?(?:'|\")").getMatch(0);
            }
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = Encoding.htmlDecode(dllink);
            final String ext = getFileNameExtensionFromString(dllink, ".m4v");
            downloadLink.setFinalFileName(filename + ext);
        } else {
            if (br.containsHTML("(>Photo not found|>The photo could not be found|<title>EroProfile</title>)")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            String filename = getFilename();
            dllink = br.getRegex("<\\s*div\\s+class=\"viewPhotoContainer\">\\s*<\\s*a\\s+href=\"((?:https?:)?//[^<>\"]*?)\"").getMatch(0);
            if (dllink == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            dllink = Encoding.htmlDecode(dllink);
            final String ext = getFileNameExtensionFromString(dllink, ".jpg");
            downloadLink.setFinalFileName(filename + ext);
        }
        final Browser br2 = br.cloneBrowser();
        // In case the link redirects to the finallink
        br2.setFollowRedirects(true);
        URLConnectionAdapter con = null;
        try {
            con = br2.openHeadConnection(dllink);
            if (!con.getContentType().contains("html")) {
                downloadLink.setDownloadSize(con.getLongContentLength());
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void handleFree(DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        if (br.containsHTML(NOACCESS)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        } else if (br.containsHTML(PREMIUMONLY)) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
        }
        doFree(downloadLink);
    }

    public void doFree(DownloadLink downloadLink) throws Exception {
        // Resume & chunks works but server will only send 99% of the data if used
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, false, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    public void login(final Browser br, final Account account, final boolean force) throws Exception {
        synchronized (LOCK) {
            try {
                br.setCookiesExclusive(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null && !force) {
                    br.setCookies(account.getHoster(), cookies);
                    return;
                }
                br.getHeaders().put("Accept-Language", "en-us,en;q=0.5");
                br.setCookie("https://eroprofile.com/", "lang", "en");
                br.setFollowRedirects(false);
                br.getHeaders().put("X_REQUESTED_WITH", "XMLHttpRequest");
                br.postPage("https://www." + account.getHoster() + "/ajax_v1.php", "p=profile&a=login&username=" + Encoding.urlEncode(account.getUser()) + "&password=" + Encoding.urlEncode(account.getPass()));
                if (!isLoggedin()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (final PluginException e) {
                account.clearCookies("");
                throw e;
            }
        }
    }

    private boolean isLoggedin() {
        return br.getCookie(MAINPAGE, "memberID", Cookies.NOTDELETEDPATTERN) != null;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        try {
            login(br, account, true);
        } catch (final PluginException e) {
            throw e;
        }
        ai.setUnlimitedTraffic();
        ai.setStatus("Free Account");
        account.setType(AccountType.FREE);
        return ai;
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        requestFileInformation(link);
        login(br, account, false);
        br.setFollowRedirects(false);
        requestFileInformation(link);
        doFree(link);
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    private String getFilename() throws PluginException {
        String filename = br.getRegex("<tr><th>Title:</th><td>([^<>\"]*?)</td></tr>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<title>EroProfile \\- ([^<>\"]*?)</title>").getMatch(0);
        }
        if (filename == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        return Encoding.htmlDecode(filename.trim());
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}
