/*
 *
 * Paros and its related class files.
 * 
 * Paros is an HTTP/HTTPS proxy for assessing web application security.
 * Copyright (C) 2003-2004 Chinotec Technologies Company
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Clarified Artistic License
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Clarified Artistic License for more details.
 * 
 * You should have received a copy of the Clarified Artistic License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
// ZAP: 2011/05/15 Support for exclusions
// ZAP: 2011/08/30 Support for scanner levels
// ZAP: 2012/02/18 Dont log errors for temporary hrefs
// ZAP: 2012/03/15 Changed the method getPathRegex to use the class StringBuilder
// instead of StringBuffer and replaced some string concatenations with calls
// to the method append of the class StringBuilder. Removed unnecessary castings
// in the methods scanSingleNode, notifyHostComplete and pluginCompleted. Changed
// the methods processPlugin and pluginCompleted to use Long.valueOf instead of
// creating a new Long.
// ZAP: 2012/04/25 Added @Override annotation to the appropriate method.
// ZAP: 2012/07/30 Issue 43: Added support for Scope
// ZAP: 2012/08/07 Issue 342 Support the HttpSenderListener
// ZAP: 2012/08/07 Renamed Level to AlertThreshold and added support for AttackStrength
// ZAP: 2012/08/31 Enabled control of AttackStrength
// ZAP: 2012/11/22 Issue 421: Cleanly shut down any active scan threads on shutdown
// ZAP: 2013/01/19 Issue 460 Add support for a scan progress dialog
// ZAP: 2013/03/08 Added some debug logging
// ZAP: 2014/01/16 Add support to plugin skipping
// ZAP: 2014/02/21 Issue 1043: Custom active scan dialog
// ZAP: 2014/03/23 Issue 1084: NullPointerException while selecting a node in the "Sites" tab
// ZAP: 2014/04/01 Changed to set a name to created threads.

package org.parosproxy.paros.core.scanner;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.parosproxy.paros.common.ThreadPool;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.ConnectionParam;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpSender;
import org.zaproxy.zap.users.User;

public class HostProcess implements Runnable {

    private static Logger log = Logger.getLogger(HostProcess.class);
    private static DecimalFormat decimalFormat = new java.text.DecimalFormat("###0.###");
    
    private SiteNode startNode = null;
    private boolean isStop = false;
    private PluginFactory pluginFactory = null;
    private ScannerParam scannerParam = null;
    private HttpSender httpSender = null;
    private ThreadPool threadPool = null;
    private Scanner parentScanner = null;
    private String hostAndPort = "";
    private Analyser analyser = null;
    private Kb kb = null;
	private User user = null;

    // time related 
    private Map<Long, Long> mapPluginStartTime = new HashMap<Long, Long>();
    private Set<Integer> listPluginIdSkipped = new HashSet<Integer>();
    private long hostProcessStartTime = 0;

    /**
     *
     */
    public HostProcess(String hostAndPort, Scanner parentScanner, 
    		ScannerParam scannerParam, ConnectionParam connectionParam, PluginFactory pluginFactory) {
        super();
        this.hostAndPort = hostAndPort;
        this.parentScanner = parentScanner;
        this.scannerParam = scannerParam;
        
        this.pluginFactory = pluginFactory;
        httpSender = new HttpSender(connectionParam, true, HttpSender.ACTIVE_SCANNER_INITIATOR);
        httpSender.setUser(this.user);
        int maxNumberOfThreads;
        if (scannerParam.getHandleAntiCSRFTokens()) {
            // Single thread if handling anti CSRF tokens, otherwise token requests might get out of step
            maxNumberOfThreads = 1;
        
        } else {
            maxNumberOfThreads = scannerParam.getThreadPerHost();
        }
        threadPool = new ThreadPool(maxNumberOfThreads, "ZAP-ActiveScanner-");
    }

    public void setStartNode(SiteNode startNode) {
        this.startNode = startNode;
    }

    public void stop() {
        isStop = true;
        getAnalyser().stop();
    }

    @Override
    public void run() {
        log.debug("HostProcess.run");

        hostProcessStartTime = System.currentTimeMillis();
        getAnalyser().start(startNode);

        Plugin plugin = null;
        while (!isStop() && pluginFactory.existPluginToRun()) {
            plugin = pluginFactory.nextPlugin();
            if (plugin != null) {
                plugin.setDelayInMs(this.scannerParam.getDelayInMs());
                plugin.setDefaultAlertThreshold(this.scannerParam.getAlertThreshold());
                plugin.setDefaultAttackStrength(this.scannerParam.getAttackStrength());
                processPlugin(plugin);
            
            } else {
                // waiting for dependency - no test ready yet
                Util.sleep(1000);
            }
        }
        
        threadPool.waitAllThreadComplete(300000);
        notifyHostProgress(null);
        notifyHostComplete();
        getHttpSender().shutdown();
    }

    private void processPlugin(Plugin plugin) {
        log.info("start host " + hostAndPort + " | " + plugin.getCodeName()
                + " strength " + plugin.getAttackStrength() + " threshold " + plugin.getAlertThreshold());
        
        mapPluginStartTime.put(Long.valueOf(plugin.getId()), Long.valueOf(System.currentTimeMillis()));
        
        if (plugin instanceof AbstractHostPlugin) {
            scanSingleNode(plugin, startNode);
        
        } else if (plugin instanceof AbstractAppPlugin) {
            traverse(plugin, startNode, true);
            threadPool.waitAllThreadComplete(600000);
            pluginCompleted(plugin);
        }
    }

    private void traverse(Plugin plugin, SiteNode node) {
        this.traverse(plugin, node, false);
    }

    private void traverse(Plugin plugin, SiteNode node, boolean incRelatedSiblings) {
        if (node == null || plugin == null) {
            return;
        }
        
        log.debug("traverse: plugin=" + plugin.getName() + " node=" + node.getNodeName() + " heir=" + node.getHierarchicNodeName());

        Set<SiteNode> parentNodes = new HashSet<>();
        parentNodes.add(node);

        scanSingleNode(plugin, node);

        if (incRelatedSiblings) {
            // Also match siblings with the same hierarchic name
            // If we dont do this http://localhost/start might match the GET variant in the Sites tree and miss the hierarchic node
            // note that this is only done for the top level.
            SiteNode sibling = node;
            while ((sibling = (SiteNode) sibling.getPreviousSibling()) != null) {
                if (node.getHierarchicNodeName().equals(sibling.getHierarchicNodeName())) {
                    log.debug("traverse: adding related sibling " + sibling.getNodeName());
                    parentNodes.add(sibling);
                }
            }
            
            sibling = node;
            while ((sibling = (SiteNode) sibling.getNextSibling()) != null) {
                if (node.getHierarchicNodeName().equals(sibling.getHierarchicNodeName())) {
                    log.debug("traverse: adding related sibling " + sibling.getNodeName());
                    parentNodes.add(sibling);
                }
            }
        }

        if (parentScanner.scanChildren()) {
            for (SiteNode pNode : parentNodes) {
                // ZAP: Added control for skipping
                for (int i = 0; i < pNode.getChildCount() && !isStop() && !isSkipped(plugin); i++) {
                    // ZAP: Implement pause and resume
                    while (parentScanner.isPaused() && !isStop()) {
                        Util.sleep(500);
                    }

                    try {
                        traverse(plugin, (SiteNode) pNode.getChildAt(i));
                        
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                }
            }
        }
    }

    protected boolean nodeInScope(SiteNode node) {
        return parentScanner.urlInScope(node.getHierarchicNodeName());
    }

    /**
     * Create new plugin instance and run against a node
     *
     * @param plugin
     * @param node. If node == null, run for server level plugin
     */
    private void scanSingleNode(Plugin plugin, SiteNode node) {
        log.debug("scanSingleNode node plugin=" + plugin.getName() + " node=" + node);
        Thread thread = null;
        Plugin test = null;
        HttpMessage msg = null;

        // do not poll for isStop here to allow every plugin to run but terminate immediately.
        //if (isStop()) return;

        try {
            if (node == null || node.getHistoryReference() == null) {
                log.debug("scanSingleNode node or href null, returning: node=" + node);
                return;
            }
            
            if (HistoryReference.TYPE_SCANNER == node.getHistoryReference().getHistoryType()) {
                log.debug("Ignoring \"scanner\" type href");
                return;
            }

            if (!nodeInScope(node)) {
                log.debug("scanSingleNode node not in scope");
                return;
            }
            
            msg = node.getHistoryReference().getHttpMessage();

            if (msg == null) {
                // Likely to be a temporary node
                log.debug("scanSingleNode msg null");
                return;
            }

            test = plugin.getClass().newInstance();
            test.setConfig(plugin.getConfig());
            test.setDelayInMs(plugin.getDelayInMs());
            test.setDefaultAlertThreshold(plugin.getAlertThreshold());
            test.setDefaultAttackStrength(plugin.getAttackStrength());
            test.init(msg, this);
            notifyHostProgress(plugin.getName() + ": " + msg.getRequestHeader().getURI().toString());

        } catch (Exception e) {
            if (node != null) {
                log.error(e.getMessage() + " " + node.getNodeName(), e);
                
            } else {
                log.error(e.getMessage(), e);
            }
            
            return;
        }

        do {
            thread = threadPool.getFreeThreadAndRun(test);
            if (thread == null) {
                Util.sleep(200);
            }
            
        } while (thread == null);

    }

    /**
     * @return Returns the httpSender.
     */
    public HttpSender getHttpSender() {
        return httpSender;
    }

    public boolean isStop() {
        return (isStop || parentScanner.isStop());
    }
    
    public boolean isPaused() {
        return parentScanner.isPaused();
    }

    private void notifyHostProgress(String msg) {
        int percentage = 0;
        if (pluginFactory.totalPluginToRun() == 0) {
            percentage = 100;
    
        } else {
            percentage = (100 * pluginFactory.totalPluginCompleted() / pluginFactory.totalPluginToRun());
        }
        
        parentScanner.notifyHostProgress(hostAndPort, msg, percentage);
    }

    private void notifyHostComplete() {
        long diffTimeMillis = System.currentTimeMillis() - hostProcessStartTime;
        String diffTimeString = decimalFormat.format(diffTimeMillis / 1000.0) + "s";
        log.info("completed host " + hostAndPort + " in " + diffTimeString);
        parentScanner.notifyHostComplete(hostAndPort);
    }

    // ZAP: notify parent
    public void notifyNewMessage(HttpMessage msg) {
        parentScanner.notifyNewMessage(msg);
    }

    public void alertFound(Alert alert) {
        parentScanner.notifyAlertFound(alert);
    }

    public Analyser getAnalyser() {
        if (analyser == null) {
            analyser = new Analyser(getHttpSender(), this);
        }
        return analyser;
    }

    public boolean handleAntiCsrfTokens() {
        return this.scannerParam.getHandleAntiCSRFTokens();
    }

    /**
     * Skip the current executing plugin
     * @param plugin 
     */
    public void pluginSkipped(Plugin plugin) {
        if (pluginFactory.isRunning(plugin)) {
            listPluginIdSkipped.add(plugin.getId());
        }
    }

    /**
     * 
     * @param plugin
     * @return 
     */
    public boolean isSkipped(Plugin plugin) {
        return (!listPluginIdSkipped.isEmpty() && listPluginIdSkipped.contains(plugin.getId()));
    }
    
    void pluginCompleted(Plugin plugin) {
        Object obj = mapPluginStartTime.get(Long.valueOf(plugin.getId()));
        StringBuilder sb = new StringBuilder();
        if (isStop()) {
            sb.append("stopped host/plugin ");
 
        // ZAP: added skipping notifications
        } else if (isSkipped(plugin)) {
            sb.append("skipped plugin ");
                    
        } else {
            sb.append("completed host/plugin ");
        }
        
        sb.append(hostAndPort).append(" | ").append(plugin.getCodeName());
        if (obj != null) {
            long startTimeMillis = ((Long) obj).longValue();
            long diffTimeMillis = System.currentTimeMillis() - startTimeMillis;
            String diffTimeString = decimalFormat.format(diffTimeMillis / 1000.0) + "s";
            sb.append(" in ").append(diffTimeString);
        }
        
        log.info(sb.toString());
        pluginFactory.setRunningPluginCompleted(plugin);
        notifyHostProgress(null);
    }

    /**
     * 
     * @return 
     */
    Kb getKb() {
        if (kb == null) {
            kb = new Kb();
        }
        
        return kb;
    }

    protected ScannerParam getScannerParam() {
        return scannerParam;
    }

    public List<Plugin> getPending() {
        return this.pluginFactory.getPending();
    }

    public List<Plugin> getRunning() {
        return this.pluginFactory.getRunning();
    }

    public List<Plugin> getCompleted() {
        return this.pluginFactory.getCompleted();
    }

	/**
	 * Set the user to scan as. If null then the current session will be used.
	 * @param user
	 */
    public void setUser(User user) {
		this.user = user;
		if (httpSender != null) {
			httpSender.setUser(user);
		}
	}
}