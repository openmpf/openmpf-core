/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2021 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2021 The MITRE Corporation                                       *
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

#ifndef MPF_BOUNDINGBOXIMAGEHANDLE_H
#define MPF_BOUNDINGBOXIMAGEHANDLE_H

#include <string>

#include <opencv2/core.hpp>

#include <MPFVideoCapture.h>

class BoundingBoxImageHandle {
public:
    static constexpr bool markExemplar = false;
    static constexpr bool showFrameNumbers = false;

    BoundingBoxImageHandle(std::string sourcePath, std::string destinationPath);

    cv::Size GetFrameSize() const;

    bool Read(cv::Mat &frame);

    void HandleMarkedFrame(const cv::Mat& frame);

private:
    std::string sourcePath_;

    std::string destinationPath_;

    MPF::COMPONENT::MPFVideoCapture videoCapture_;

    bool frameRead_ = false;
};

#endif //MPF_BOUNDINGBOXIMAGEHANDLE_H
