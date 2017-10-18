#!/bin/bash
#############################################################################
# NOTICE                                                                    #
#                                                                           #
# This software (or technical data) was produced for the U.S. Government    #
# under contract, and is subject to the Rights in Data-General Clause       #
# 52.227-14, Alt. IV (DEC 2007).                                            #
#                                                                           #
# Copyright 2017 The MITRE Corporation. All Rights Reserved.                #
#############################################################################

#############################################################################
# Copyright 2017 The MITRE Corporation                                      #
#                                                                           #
# Licensed under the Apache License, Version 2.0 (the "License");           #
# you may not use this file except in compliance with the License.          #
# You may obtain a copy of the License at                                   #
#                                                                           #
#    http://www.apache.org/licenses/LICENSE-2.0                             #
#                                                                           #
# Unless required by applicable law or agreed to in writing, software       #
# distributed under the License is distributed on an "AS IS" BASIS,         #
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  #
# See the License for the specific language governing permissions and       #
# limitations under the License.                                            #
#############################################################################

# Run the mpf-clean ansible playbook, used to cleanup previously deployed mpf executables, jars and libs
echo "Performing cleanup of obsolete MPF software and data ..."
echo ""
echo "Please provide an existing priv. user that will be used to cleanup obsolete MPF software and data on the remote machines."
echo -n "Username: "
read USER_NAME
#This touch is for an ansible bug... https://github.com/ansible/ansible/issues/10057
touch ~/.ssh/known_hosts
ansible-playbook /opt/mpf/manage/ansible/mpf-cleanup.yml --ask-pass --user $USER_NAME --become --ask-become-pass

# Run the deployment python script
python /opt/mpf/manage/run-install.py

# Source the MPF profile script
source /etc/profile.d/mpf.sh

