/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2018 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2018 The MITRE Corporation                                       *
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


#include "ExecutorErrors.h"
#include "ExecutorUtils.h"
#include "StandardInWatcher.h"

#include "StreamingVideoCapture.h"

namespace MPF { namespace COMPONENT {

    StreamingVideoCapture::StreamingVideoCapture(log4cxx::LoggerPtr &logger, const std::string &video_uri)
            : logger_(logger)
            , video_uri_(video_uri)
            , cvVideoCapture_(video_uri) {

        if (!cvVideoCapture_.isOpened()) {
            throw FatalError(ExitCode::UNABLE_TO_CONNECT_TO_STREAM,
                             "Unable to connect to stream: " + video_uri);
        }
    }

    bool StreamingVideoCapture::Read(cv::Mat frame) {
        return cvVideoCapture_.read(frame);
    }


    void StreamingVideoCapture::ReadWithRetry(cv::Mat &frame) {
        if (Read(frame)) {
            return;
        }

        LOG4CXX_WARN(logger_, "Failed to read frame. Will retry forever.")
        ExecutorUtils::RetryWithBackOff(
                [this, &frame] {
                    return DoReadRetry(frame);
                },
                [this] (const ExecutorUtils::sleep_duration_t &duration) {
                    BetweenRetrySleep(duration);
                }
        );
    }


    bool StreamingVideoCapture::DoReadRetry(cv::Mat &frame) {
        bool reopened = cvVideoCapture_.open(video_uri_);
        if (!reopened) {
            LOG4CXX_WARN(logger_, "Failed to re-connect to video stream.");
            return false;
        }

        LOG4CXX_WARN(logger_, "Successfully re-connected to video stream.");

        bool was_read = cvVideoCapture_.read(frame);
        if (was_read) {
            LOG4CXX_WARN(logger_, "Successfully read frame after re-connecting to video stream.");
            return true;
        }

        LOG4CXX_WARN(logger_, "Failed to read frame after successfully re-connecting to video stream.");
        return false;
    }
}}
