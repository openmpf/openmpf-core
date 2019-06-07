
#############################################################################
# NOTICE                                                                    #
#                                                                           #
# This software (or technical data) was produced for the U.S. Government    #
# under contract, and is subject to the Rights in Data-General Clause       #
# 52.227-14, Alt. IV (DEC 2007).                                            #
#                                                                           #
# Copyright 2019 The MITRE Corporation. All Rights Reserved.                #
#############################################################################

#############################################################################
# Copyright 2019 The MITRE Corporation                                      #
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

# Example profile.d bash init script for MPF
#
# This file is used to set up the runtime environment for the MPF system, particularly the
# node manager.
#
# NOTE: In a deployed environment managed by ansible, this file is set up for you by ansible.
# The variables defined here and their example values are mainly for use in development VM
# environments.
#
# Set appropriate values for MPF_USER and MPF_HOME, and modify other variables with correct
# values for the given runtime environment.  Then copy this file to /etc/profile.d/mpf.sh
export no_proxy=localhost

export MPF_USER=mpf
export MPF_HOME=/home/mpf/openmpf-projects/openmpf/trunk/install
export MPF_LOG_PATH=$MPF_HOME/share/logs
export MASTER_MPF_NODE=$HOSTNAME
export THIS_MPF_NODE=$HOSTNAME
export CORE_MPF_NODES=$THIS_MPF_NODE

export JAVA_HOME=/usr/java/latest

export JGROUPS_TCP_ADDRESS=$THIS_MPF_NODE
export JGROUPS_TCP_PORT=7800
export JGROUPS_FILE_PING_LOCATION=$MPF_HOME/share/nodes

# although CATALINA_OPTS is set in /opt/apache-tomcat/bin/setenv.sh, it's necessary to also set it here for the tomcat8-maven-plugin
export CATALINA_OPTS="-server -Xms256m -XX:PermSize=512m -XX:MaxPermSize=512m -Djava.library.path=$MPF_HOME/lib -Dtransport.guarantee='NONE' -Dweb.rest.protocol='http'"

export ACTIVE_MQ_HOST=$MASTER_MPF_NODE
export MYSQL_HOST=localhost
export REDIS_HOST=localhost

export ACTIVE_MQ_BROKER_URI="failover://(tcp://$ACTIVE_MQ_HOST:61616)?jms.prefetchPolicy.all=1&startupMaxReconnectAttempts=1"

# enable tab completion for mpf script
command -v register-python-argcomplete > /dev/null && eval "$(register-python-argcomplete mpf)"
