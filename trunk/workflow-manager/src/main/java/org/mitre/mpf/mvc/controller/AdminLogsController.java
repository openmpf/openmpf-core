 /******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2019 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2019 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.mvc.controller;

 import org.mitre.mpf.mvc.util.tailer.FilteredMpfLogTailerListener;
import org.mitre.mpf.mvc.util.tailer.MpfLogLevel;
import org.mitre.mpf.mvc.util.tailer.MpfLogTailer;
import org.mitre.mpf.wfm.WfmProcessingException;
import org.mitre.mpf.wfm.util.PropertiesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;

// NOTE: Don't use @Scope("request") because we don't need to access to the session
// and the additional overhead associated with creating the log file maps per request.

@Controller
@Scope("singleton")
public class AdminLogsController
{
	private static final Logger log = LoggerFactory.getLogger(AdminLogsController.class);

    private final FilenameFilter filenameFilter = new LogFilenameFilter();

    // a map between nodes and the list of log names for that node
    private Map<String, Set<String>> nodesAndLogs = new HashMap<String, Set<String>>();

    // a map between nodes and another map between log name and log File
    private Map<String, Map<String, File>> nodesAndLogFiles = new HashMap<String, Map<String, File>>();

    @Autowired
    private PropertiesUtil propertiesUtil;

	@RequestMapping(value = "/adminLogs", method = RequestMethod.GET)
	public ModelAndView adminLogs(HttpServletRequest request) throws WfmProcessingException {
		return new ModelAndView("admin_logs");
	}

    @RequestMapping(value = "/adminLogsMap", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Set<String>> getLogsMap() throws WfmProcessingException {

        nodesAndLogs = getNodesAndLogs();
        return nodesAndLogs;
    }

    @RequestMapping(value = "/adminLogsUpdate", method = RequestMethod.GET)
    @ResponseBody
    public Map<String, Object> updateLog(@RequestParam(value = "nodeSelection") String nodeSelection,
                                         @RequestParam(value = "logSelection") String logSelection,
                                         @RequestParam(value = "logLevelSelection") String logLevelSelection,
                                         @RequestParam(value = "maxLines") int maxLines,
                                         @RequestParam(value = "cycleId") int cycleId,
                                         @RequestParam(value = "lastChecked") long lastChecked,
                                         @RequestParam(value = "lastPosition") long lastPosition,
                                         @RequestParam(value = "lastLineLevel", required = false) String lastLineLevel)
        throws WfmProcessingException {
        /*
        log.info("updateLog: nodeSelection={}, logSelection={}, logLevelSelection={}, maxLines={}, " +
            "cycleId={}, lastChecked={}, lastPosition={}, lastLineLevel={}",
        nodeSelection, logSelection, logLevelSelection, maxLines,, cycleId, lastChecked, lastPosition, lastLineLevel);
        */

        // check for empty nodes and logs lists; this won't happen ordinarily but could happen if tomcat is restarted
        // after the user has viewed logs(?)
        if (nodesAndLogFiles.isEmpty()) {
            nodesAndLogs = getNodesAndLogs();
        }
        File logFile = null;
        if (nodesAndLogFiles.containsKey(nodeSelection)) {
            // get the log files for this node
            Map<String, File> logFiles = nodesAndLogFiles.get(nodeSelection);

            if (logFiles.containsKey(logSelection)) {
                // get the File object for this log
                logFile = logFiles.get(logSelection);
            }
        }

        Map<String, Object> json = new HashMap<>();
        if (logFile != null && logFile.exists()) {
            // log.info("creating tailer for {}", logFile );
            FilteredMpfLogTailerListener tailerListener =
                    new FilteredMpfLogTailerListener(MpfLogLevel.toLevel(logLevelSelection), MpfLogLevel.toLevel(lastLineLevel, null));
            MpfLogTailer tailer = new MpfLogTailer(logFile, tailerListener, lastChecked, lastPosition);

            int numLines = tailer.readLines(maxLines);
            List<String> lines = tailerListener.purgeLines();
            boolean skippedAhead = false;

            // if tailing the file for the first time and we haven't read to the end of the file ...
            if (lastChecked == -1 && lastPosition == -1 && numLines == maxLines) {
                while (tailer.readLines(1) > 0) {
                    lines.remove(0);
                    lines.add(tailerListener.purgeLines().get(0));
                    skippedAhead = true;
                }
            }

            StringBuilder buff = new StringBuilder();
            for (String line : lines) {
                buff.append(line);
                buff.append("\n");
            }

            String lastLineLevelStr = null;
            if (tailerListener.getLastLineLevel() != null) {
                lastLineLevelStr = tailerListener.getLastLineLevel().toString();
            }

            json.put("logExists", true);
            json.put("text", buff.toString().trim());
            json.put("numLines", numLines);
            json.put("cycleId", cycleId);
            json.put("lastChecked", tailer.getLastChecked());
            json.put("lastPosition", tailer.getLastPosition());
            json.put("lastLineLevel", lastLineLevelStr);
            json.put("skippedAhead", skippedAhead);

            tailer.cleanup();
        } else {
            // log.info("not creating tailer because log file {} doesn't exist yet", logFile.getAbsolutePath());
            json.put("logExists", false);
            json.put("cycleId", cycleId);
        }
        return json;
    }

    private Map<String, Set<String>> getNodesAndLogs() throws WfmProcessingException {

        // this method has a side effect of populating nodesAndLogFiles, the map of host to [a map of simple logname to
        // File object]
        Map<String, Set<String>> nodeLogMap = new HashMap<String, Set<String>>();

        File logParentDir = new File(propertiesUtil.getLogParentDir());
        if (logParentDir.exists() && logParentDir.isDirectory()) {

            // iterate over hostname directories within parent log dir
            for (File hostDir : logParentDir.listFiles()) {
                // log.info("Adding {} to list of hostnames", hostDir.getName());
                File logDir = new File(hostDir.getAbsolutePath(), "/log/");

                if (logDir.exists() && logDir.isDirectory()) {
                    Set<String> lognames = new TreeSet<String>();
                    Map<String, File> lognameFileMap = new HashMap<String, File>();

                    // iterate over files within <parent log dir>/<hostname>/log/<*.log>
                    for (File file : logDir.listFiles(filenameFilter)) {
                        String filename = file.getName();
                        String logname = filename.substring(0, filename.indexOf('.'));
                        lognames.add(logname);
                        // log.info("Adding {} to list of today's log files", file);
                        lognameFileMap.put(logname, file);
                    }

                    // log.info("Adding host {} with logs {}", hostDir.getName(), lognames);
                    nodeLogMap.put(hostDir.getName(), lognames);
                    nodesAndLogFiles.put(hostDir.getName(), lognameFileMap);
                } else {
                    throw new WfmProcessingException(String.format("Failed to find log directory %s", logDir.getAbsolutePath()));
                }
            }
        } else {
            throw new WfmProcessingException(String.format("Failed to find parent log directory %s",
                propertiesUtil.getLogParentDir()));
        }
        return nodeLogMap;
    }


    public class LogFilenameFilter implements FilenameFilter {
        @Override
        public boolean accept(File directory, String filename) {
            return filename.endsWith(".log");
        }
    }
}




