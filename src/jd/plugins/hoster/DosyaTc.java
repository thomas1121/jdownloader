//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
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

import java.io.IOException;

import jd.PluginWrapper;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.SiteType.SiteTemplate;

import org.appwork.utils.formatter.SizeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "dosya.tc" }, urls = { "https?://[\\w\\.]*?dosya\\.tc/(?!index).+\\.html" })
public class DosyaTc extends PluginForHost {
    public DosyaTc(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return "http://www.dosya.tc/index.php?page=tos";
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink downloadLink) throws IOException, PluginException {
        this.setBrowserExclusive();
        br.setFollowRedirects(true);
        br.getPage(downloadLink.getDownloadURL());
        // For JD2
        if (br.containsHTML(">Dosya bulunamadı|>Dosya bulunamadi")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        // For the Stable
        if (br.containsHTML("r>Dosya bulunamadý|>Dosya bulunamadi")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.getURL().endsWith("dosya.tc")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        String filename = br.getRegex("<td><b>Dosya Adı(/File Name)?</b></td>[\t\n\r ]+<td><b>([^<>\"]*?)</b></td>").getMatch(1);
        if (filename == null) {
            filename = br.getRegex("<b>Dosya Adi/File Name</b></td> <td><b>([^<>\"]*?)</b></td>").getMatch(0);
        }
        if (filename == null) {
            filename = br.getRegex("Dosya Adı : <span>([^<>\"]*?)</span>").getMatch(0);
        }
        String filesize = br.getRegex("<td><b>Dosya Boyutu(/File Size)?</b></td>[\t\n\r ]+<td><b>([^<>\"]*?)</b></td>").getMatch(1);
        if (filesize == null) {
            filesize = br.getRegex("Dosya Boyutu : <span>([^<>\"]*?)</span>").getMatch(0);
        }
        if (filename == null || filesize == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        downloadLink.setName(filename);
        downloadLink.setDownloadSize(SizeFormatter.getSize(filesize));
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        // String dllink = br.getRegex("value=\"Download\" onClick=\"window\\.location=.*?(http.*?).'\">").getMatch(0);
        String dllink = br.getRegex("window\\.open\\(\"(https?://[^\"]*dosya.*?)\"").getMatch(0);
        if (dllink == null) {
            dllink = br.getRegex("<a href=\"([^\"]+)\" id=\"download\"").getMatch(0);
        }
        if (dllink == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, dllink, true, 1);
        if (dl.getConnection().getContentType().contains("html")) {
            logger.warning("This link seems not to be a file...");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public SiteTemplate siteTemplateType() {
        return SiteTemplate.DaddyScripts_FileHostV2;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public void resetPluginGlobals() {
    }
}