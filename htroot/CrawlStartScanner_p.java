/**
 *  CrawlStartScanner_p
 *  Copyright 2010 by Michael Peter Christen, mc@yacy.net, Frankfurt am Main, Germany
 *  First released 12.12.2010 at http://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.yacy.cora.order.Base64Order;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.Scanner;
import net.yacy.cora.protocol.Scanner.Access;
import net.yacy.data.WorkTables;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.logging.Log;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.search.query.SearchEventCache;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class CrawlStartScanner_p
{

    private final static int CONCURRENT_RUNNER = 200;

    public static serverObjects respond(
        @SuppressWarnings("unused") final RequestHeader header,
        final serverObjects post,
        final serverSwitch env) {

        final serverObjects prop = new serverObjects();
        final Switchboard sb = (Switchboard) env;

        // clean up all search events
        SearchEventCache.cleanupEvents(true);

        prop.put("noserverdetected", 0);
        prop.put("hosts", "");
        prop.put("intranet.checked", sb.isIntranetMode() ? 1 : 0);

        int timeout = sb.isIntranetMode() ? 200 : 3000;
        timeout = post == null ? timeout : post.getInt("timeout", timeout);

        // make a scanhosts entry
        String hosts = post == null ? "" : post.get("scanhosts", "");
        final Set<InetAddress> ips = Domains.myIntranetIPs();
        prop.put("intranethosts", ips.toString());
        prop.put("intranetHint", sb.isIntranetMode() ? 0 : 1);
        if ( hosts.isEmpty() ) {
            InetAddress ip;
            if ( sb.isIntranetMode() ) {
                if ( !ips.isEmpty() ) {
                    ip = ips.iterator().next();
                } else {
                    ip = Domains.dnsResolve("192.168.0.1");
                }
            } else {
                ip = Domains.myPublicLocalIP();
                if ( Domains.isThisHostIP(ip) ) {
                    ip = sb.peers.mySeed().getInetAddress();
                }
            }
            if ( ip != null ) {
                hosts = ip.getHostAddress();
            }
        }
        prop.put("scanhosts", hosts);

        // parse post requests
        if ( post != null ) {
            int repeat_time = 0;
            String repeat_unit = "seldays";

            // check scheduler
            if ( post.get("rescan", "").equals("scheduler") ) {
                repeat_time = post.getInt("repeat_time", -1);
                repeat_unit = post.get("repeat_unit", "selminutes"); // selminutes, selhours, seldays
            }

            final int subnet = post.getInt("subnet", 24);

            // scan a range of ips
            if (post.containsKey("scan")) {
                final Set<InetAddress> scanbase = new HashSet<InetAddress>();
                
                // select host base to scan
                if ("hosts".equals(post.get("source", ""))) {
                    for (String host: hosts.split(",")) {
                        if (host.startsWith("http://")) host = host.substring(7);
                        if (host.startsWith("https://")) host = host.substring(8);
                        if (host.startsWith("ftp://")) host = host.substring(6);
                        if (host.startsWith("smb://")) host = host.substring(6);
                        final int p = host.indexOf('/', 0);
                        if (p >= 0) host = host.substring(0, p);
                        if (host.length() > 0) scanbase.add(Domains.dnsResolve(host));
                    }
                }
                if ("intranet".equals(post.get("source", ""))) {
                    scanbase.addAll(Domains.myIntranetIPs());
                }
                
                // start a scanner
                final Scanner scanner = new Scanner(scanbase, CONCURRENT_RUNNER, timeout);
                List<InetAddress> addresses = scanner.genlist(subnet);
                if ("on".equals(post.get("scanftp", ""))) scanner.addFTP(addresses);
                if ("on".equals(post.get("scanhttp", ""))) scanner.addHTTP(addresses);
                if ("on".equals(post.get("scanhttps", ""))) scanner.addHTTPS(addresses);
                if ("on".equals(post.get("scansmb", ""))) scanner.addSMB(addresses);
                scanner.start();
                scanner.terminate();
                if ("on".equals(post.get("accumulatescancache", "")) && !"scheduler".equals(post.get("rescan", ""))) {
                    Scanner.scancacheExtend(scanner);
                } else {
                    Scanner.scancacheReplace(scanner);
                }
            }

            // check crawl request
            if ( post.containsKey("crawl") ) {
                // make a pk/url mapping
                final Iterator<Map.Entry<Scanner.Service, Scanner.Access>> se = Scanner.scancacheEntries();
                final Map<byte[], DigestURI> pkmap =
                    new TreeMap<byte[], DigestURI>(Base64Order.enhancedCoder);
                while (se.hasNext()) {
                    final Scanner.Service u = se.next().getKey();
                    DigestURI uu;
                    try {
                        uu = DigestURI.toDigestURI(u.url());
                        pkmap.put(uu.hash(), uu);
                    } catch ( final MalformedURLException e ) {
                        Log.logException(e);
                    }
                }
                // search for crawl start requests in this mapping
                for ( final Map.Entry<String, String> entry : post.entrySet() ) {
                    if ( entry.getValue().startsWith("mark_") ) {
                        final byte[] pk = entry.getValue().substring(5).getBytes();
                        final DigestURI url = pkmap.get(pk);
                        if ( url != null ) {
                            String path = "/Crawler_p.html?createBookmark=off&xsstopw=off&crawlingDomMaxPages=10000&intention=&range=domain&indexMedia=on&recrawl=nodoubles&xdstopw=off&storeHTCache=on&sitemapURL=&repeat_time=7&crawlingQ=on&cachePolicy=iffresh&indexText=on&crawlingMode=url&mustnotmatch=&crawlingDomFilterDepth=1&crawlingDomFilterCheck=off&crawlingstart=Start%20New%20Crawl&xpstopw=off&repeat_unit=seldays&crawlingDepth=99&directDocByURL=off";
                            path += "&crawlingURL=" + url.toNormalform(true);
                            WorkTables.execAPICall(
                                Domains.LOCALHOST,
                                (int) sb.getConfigLong("port", 8090),
                                sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_B64MD5, ""),
                                path,
                                pk);
                        }
                    }
                }
            }

            // check scheduler
            if ( "scheduler".equals(post.get("rescan", "")) ) {

                // store this call as api call
                if ( repeat_time > 0 ) {
                    // store as scheduled api call
                    sb.tables.recordAPICall(
                        post,
                        "CrawlStartScanner_p.html",
                        WorkTables.TABLE_API_TYPE_CRAWLER,
                        "network scanner for hosts: " + hosts,
                        repeat_time,
                        repeat_unit.substring(3));
                }

                // execute the scan results
                if ( Scanner.scancacheSize() > 0 ) {
                    // make a comment cache
                    final Map<byte[], String> apiCommentCache = WorkTables.commentCache(sb);

                    String urlString;
                    DigestURI u;
                    try {
                        final Iterator<Map.Entry<Scanner.Service, Scanner.Access>> se =
                            Scanner.scancacheEntries();
                        Map.Entry<Scanner.Service, Scanner.Access> host;
                        while ( se.hasNext() ) {
                            host = se.next();
                            try {
                                u = DigestURI.toDigestURI(host.getKey().url());
                                urlString = u.toNormalform(true);
                                if ( host.getValue() == Access.granted
                                    && Scanner.inIndex(apiCommentCache, urlString) == null ) {
                                    String path =
                                        "/Crawler_p.html?createBookmark=off&xsstopw=off&crawlingDomMaxPages=10000&intention=&range=domain&indexMedia=on&recrawl=nodoubles&xdstopw=off&storeHTCache=on&sitemapURL=&repeat_time=7&crawlingQ=on&cachePolicy=iffresh&indexText=on&crawlingMode=url&mustnotmatch=&crawlingDomFilterDepth=1&crawlingDomFilterCheck=off&crawlingstart=Start%20New%20Crawl&xpstopw=off&repeat_unit=seldays&crawlingDepth=99";
                                    path += "&crawlingURL=" + urlString;
                                    WorkTables.execAPICall(
                                        Domains.LOCALHOST,
                                        (int) sb.getConfigLong("port", 8090),
                                        sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_B64MD5, ""),
                                        path,
                                        u.hash());
                                }
                            } catch ( final MalformedURLException e ) {
                                Log.logException(e);
                            }
                        }
                    } catch ( final ConcurrentModificationException e ) {
                    }
                }

            }
        }

        return prop;
    }
    
}
