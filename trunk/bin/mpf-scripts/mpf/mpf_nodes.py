#############################################################################
# NOTICE                                                                    #
#                                                                           #
# This software (or technical data) was produced for the U.S. Government    #
# under contract, and is subject to the Rights in Data-General Clause       #
# 52.227-14, Alt. IV (DEC 2007).                                            #
#                                                                           #
# Copyright 2018 The MITRE Corporation. All Rights Reserved.                #
#############################################################################

#############################################################################
# Copyright 2018 The MITRE Corporation                                      #
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

import argh
import base64
import getpass
import os
import urllib2

import mpf_util

@argh.arg('-w', '--workflow-manager-url', help='Url to Workflow Manager')
def list_nodes(workflow_manager_url='http://localhost:8080/workflow-manager'):
    """ List JGroups membership for nodes in the OpenMPF cluster """

    if not is_wfm_running(workflow_manager_url):
        print mpf_util.MsgUtil.yellow('Cannot determine live JGroups membership.')

        core_mpf_nodes_str = os.environ.get(CORE_MPF_NODES_ENV_VAR).strip(', ')

        if not core_mpf_nodes_str:
            raise mpf_util.MpfError(CORE_MPF_NODES_ENV_VAR + ' environment variable is not set.')

        nodes_set = set(parse_nodes_str(core_mpf_nodes_str))

        print 'Core nodes listed by ' + CORE_MPF_NODES_ENV_VAR + ' environment variable:\n' + '\n'.join(nodes_set)
        return

    username, password = get_username_and_password()
    core_nodes_list = get_all_wfm_nodes(workflow_manager_url, username, password, "core")
    spare_nodes_list = get_all_wfm_nodes(workflow_manager_url, username, password, "spare")

    print 'Core nodes: ' + str(core_nodes_list)

    if spare_nodes_list:
        print 'Spare nodes: ' + str(spare_nodes_list)
    else:
        print 'No spare nodes'


def get_username_and_password():
    print 'Enter the credentials for a Workflow Manager user:'

    username = raw_input('Username: ')
    password = getpass.getpass('Password: ')

    return username, password


def get_all_wfm_nodes(wfm_manager_url, username, password, node_type = "all"):
    endpoint_url = wfm_manager_url.rstrip('/') + '/rest/nodes/all?type=' + node_type
    request = urllib2.Request(endpoint_url)

    base64string = base64.b64encode('%s:%s' % (username, password))
    request.add_header('Authorization', 'Basic %s' % base64string)

    try:
        response = urllib2.urlopen(request)
    except IOError as err:
        raise mpf_util.MpfError('Problem connecting to ' + endpoint_url + ':\n' + str(err))

    # convert a string of '["X.X.X.X". "X.X.X.X"]' to a Python list
    nodes_str = response.read()[2:-2].translate(None, '"')
    return parse_nodes_str(nodes_str)


def is_wfm_running(wfm_manager_url):
    request = urllib2.Request(wfm_manager_url)
    request.get_method = lambda: 'HEAD'
    try:
        urllib2.urlopen(request)
        print 'Detected that the Workflow Manager is running.'
        return True
    except urllib2.URLError:
        print mpf_util.MsgUtil.yellow('Detected that the Workflow Manager is not running.')
        return False


def parse_nodes_str(nodes_str):
    return [node.strip() for node in nodes_str.split(',') if node and not node.isspace()]


CORE_MPF_NODES_ENV_VAR = 'CORE_MPF_NODES'

COMMANDS = [list_nodes]
