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

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.mitre.mpf.rest.api.AggregatePipelineStatsModel;
import org.mitre.mpf.rest.api.AllJobsStatisticsModel;
import org.mitre.mpf.wfm.data.access.JobRequestDao;
import org.mitre.mpf.wfm.data.entities.persistent.JobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Api(value = "Statistics",
     description = "Job statistics")
@Controller
@Scope("request")
public class AdminStatisticsController {
    private static final Logger log = LoggerFactory.getLogger(AdminStatisticsController.class);

    @Autowired
    private JobRequestDao jobRequestDao;

    @RequestMapping(value = "/adminStatistics", method = RequestMethod.GET)
    public ModelAndView getAdminStatistics(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView("admin_statistics");
        return mv;
    }

    /*
     * GET jobs/stats
     */
    //EXTERNAL
    @RequestMapping(value = "/rest/jobs/stats", method = RequestMethod.GET)
    @ApiOperation(value = "Compiles the AllJobsStatisticsModel using all of the submitted jobs.",
            produces = "application/json", response = AllJobsStatisticsModel.class)
    @ApiResponses(@ApiResponse(code = 200, message = "Successful response"))
    @ResponseBody
    public AllJobsStatisticsModel getAllJobsStatsRest() {
        log.debug("[/rest/jobs/stats]");
        return getAllJobsStatsInternal();
    }

    //INTERNAL
    @RequestMapping(value = "/jobs/stats", method = RequestMethod.GET)
    public
    @ResponseBody
    AllJobsStatisticsModel getAllJobsStats() {
        log.debug("[/jobs/stats]");
        return getAllJobsStatsInternal();
    }

    /*
     * private methods
     */
    private void updateSingleJobStatus(long jobId,
                                       final AggregatePipelineStatsModel singleJobStatisticModelRef, final Map<String, Object> dataRef) {
        JobRequest jobRequest = jobRequestDao.findById(jobId);

        boolean updateError = true;
        if (jobRequest == null) {
            log.error("Could not retrieve the job request for the job with id '{}'", jobId);
        } else if (singleJobStatisticModelRef == null) {
            log.error("A null SingleJobStatisticModel reference can't be updated.");
        } else if (dataRef == null) {
            log.error("A null Job Statistics Data reference can't be updated.");
        } else {
            updateError = false;
        }

        if (updateError) {
            return;
        }

        if (!dataRef.containsKey("total_time") || dataRef.get("total_time") == null) {
            dataRef.put("total_time", 0L);
        }
        if (!dataRef.containsKey("min_time") || dataRef.get("min_time") == null) {
            dataRef.put("min_time", 0L);
        }
        if (!dataRef.containsKey("max_time") || dataRef.get("max_time") == null) {
            dataRef.put("max_time", 0L);
        }
        if (!dataRef.containsKey("count") || dataRef.get("count") == null) {
            dataRef.put("count", 0L);
        }
        if (!dataRef.containsKey("valid_count") || dataRef.get("valid_count") == null) {
            dataRef.put("valid_count", 0L);
        }

        if (jobRequest.getTimeReceived() != null && jobRequest.getTimeCompleted() != null) {
            long time = jobRequest.getTimeCompleted().toEpochMilli() - jobRequest.getTimeReceived().toEpochMilli();
            dataRef.put("total_time", (Long) dataRef.get("total_time") + time);
            if ((time <= (Long) dataRef.get("min_time") && (Long) dataRef.get("min_time") > 0 && time > 0) || (Long) dataRef.get("min_time") == 0) {
                dataRef.put("min_time", time);
            }
            if (time > (Long) dataRef.get("max_time") && time > 0) {
                dataRef.put("max_time", time);
            }

            if (time > 0) {
                dataRef.put("valid_count", (Long) dataRef.get("valid_count") + 1);
            }
        }

        dataRef.put("count", (Long) dataRef.get("count") + 1);

        String state = "unknown";
        if (jobRequest.getStatus() != null) {
            state = jobRequest.getStatus().name();
        }

        HashMap<String, Long> states = new HashMap<String, Long>();
        if (dataRef.containsKey("states")) {
            states = (HashMap) dataRef.get("states");
        }

        //should never be null
        if (singleJobStatisticModelRef.getStates() != null) {
            states = singleJobStatisticModelRef.getStates();
        }

        if (!states.containsKey(state)) {
            states.put(state, 1L);
        } else {
            states.put(state, (Long) states.get(state) + 1);
        }
        //update states and the singleJobStatisticModelRef
        dataRef.put("states", states);
        singleJobStatisticModelRef.setStates(states);

        singleJobStatisticModelRef.setTotalTime((long) dataRef.get("total_time"));
        singleJobStatisticModelRef.setMinTime((long) dataRef.get("min_time"));
        singleJobStatisticModelRef.setMaxTime((long) dataRef.get("max_time"));
        singleJobStatisticModelRef.setCount((long) dataRef.get("count"));
        singleJobStatisticModelRef.setValidCount((long) dataRef.get("valid_count"));
    }

    private AllJobsStatisticsModel getAllJobsStatsInternal() {
        long start = System.currentTimeMillis();

        //TODO: this is reusing some code for get jobs - this code needs to be extracted
        List<JobRequest> jobs = jobRequestDao.findAll();

        Map<String, Map<String, Object>> map = new HashMap<String, Map<String, Object>>();
        Map<String, AggregatePipelineStatsModel> objectMap = new HashMap<String, AggregatePipelineStatsModel>();

        for (JobRequest jobRequest : jobs) {
            String pipeline = "unknown";
            if (jobRequest.getPipeline() != null) {
                pipeline = jobRequest.getPipeline();
            }
            Map<String, Object> data = new HashMap<String, Object>();
            AggregatePipelineStatsModel singleJobStatisticsModel = new AggregatePipelineStatsModel();

            //job stats from the samee pipeline are aggregated
            if (map.containsKey(pipeline)) {
                data = map.get(pipeline);
            }

            //grabbing existing to aggregate like above, but using the model here
            if (objectMap.containsKey(pipeline)) {
                //data will be added to the model object later
                singleJobStatisticsModel = objectMap.get(pipeline);
            }

            updateSingleJobStatus(jobRequest.getId(), singleJobStatisticsModel, data);

            //update the map
            map.put(pipeline, data);

            //and also can't forget to update the object map!
            objectMap.put(pipeline, singleJobStatisticsModel);
        }
        long elapsed = System.currentTimeMillis() - start;

        AllJobsStatisticsModel allJobsStatisticsModel = new AllJobsStatisticsModel();
        allJobsStatisticsModel.setElapsedTimeMs(elapsed);
        allJobsStatisticsModel.setTotalJobs(jobs.size());
        allJobsStatisticsModel.setJobTypes(map.keySet().size());
        allJobsStatisticsModel.setAggregatePipelineStatsMap(objectMap);

        return allJobsStatisticsModel;
    }
}
