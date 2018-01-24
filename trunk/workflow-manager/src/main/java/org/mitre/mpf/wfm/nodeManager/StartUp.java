/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2017 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2017 The MITRE Corporation                                       *
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

package org.mitre.mpf.wfm.nodeManager;

import org.javasimon.SimonManager;
import org.javasimon.Split;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

@Service
public class StartUp implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(StartUp.class);

	@Value("${masterNode.enabled}")
	private boolean useMasterNode;

	@Autowired
	private NodeManagerStatus nodeManagerStatus;

	@Override
	public boolean isAutoStartup() {
		//this property is not being used
		return useMasterNode;
	}

	@Override
	public void start() {
		Split split = SimonManager.getStopwatch("org.mitre.mpf.wfm.nodeManager.StartUp.start").start();
		nodeManagerStatus.init(false);
		split.stop();
	}

	@Override
	public void stop() {
//		Split split = SimonManager.getStopwatch("org.mitre.mpf.wfm.nodeManager.StartUp.start").start();
//		nodeManagerStatus.stop();
//		split.stop();
		log.info("!!! Non-async stop called.");
		doStop();
	}

	@Override
	public boolean isRunning() {
		return nodeManagerStatus.isRunning();
	}

	@Override
	public void stop(Runnable r) {
		log.info("!!! Async stop called.");
		doStop();
		r.run();
	}

	private void doStop() {
		Split split = SimonManager.getStopwatch("org.mitre.mpf.wfm.nodeManager.StartUp.start").start();
		nodeManagerStatus.stop();
		split.stop();
	}

	@Override
	public int getPhase() {
		return -1;
	}
}

