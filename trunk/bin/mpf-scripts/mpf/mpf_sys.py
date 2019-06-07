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

import time
import subprocess
import urllib2
import errno
import os
import abc
import urlparse
import signal
import mpf_util
import argh
import socket


def start_up_order():
    return ActiveMqManager, MySqlManager, RedisManager, NodeManagerManager, TomcatManager


class BaseMpfSystemDependencyManager(object):
    __metaclass__ = abc.ABCMeta  # Make this class abstract

    def __init__(self, mpf_config):
        if isinstance(mpf_config, bool):
            self._config = None
            self._shell = ShellHelper(mpf_config)
        else:
            self._config = mpf_config
            self._shell = ShellHelper(mpf_config.verbose)

    def status(self, is_stopping=False):
        """ Determines whether the component is currently running

        Returns:
            bool: True if the component is currently running
        Raises:
            subprocess.CalledProcessError: The status command returned an unrecognized exit code
        """
        try:
            return self._run_status_command() == 0
        except subprocess.CalledProcessError as err:
            if err.returncode == self._not_running_status_code():
                return False
            raise

    def status_string(self):
        """ Creates a status message that will be displayed to the user
        """
        try:
            if self.status():
                return Messages.running(self.dependency_name())
            else:
                return Messages.not_running(self.dependency_name())
        except subprocess.CalledProcessError:
            return Messages.error_during_status_check(self.dependency_name())

    def start(self):
        """ Starts the component if it isn't already running.
        """
        if self.status():
            print Messages.running(self.dependency_name())
            return

        try:
            self._run_start_command()
        except subprocess.CalledProcessError as err:
            if err.output:
                raise FailedToStartError(self.dependency_name(),
                                         self.dependency_name() + ' failed to start:\n' + err.output)
            else:
                raise FailedToStartError(self.dependency_name())

        time.sleep(.2)

        if self.status():
            print Messages.started(self.dependency_name())
        else:
            raise FailedToStartError(self.dependency_name())

    def stop(self):
        """ Stops the component if it is running
        """
        if not self.status(is_stopping=True):
            print Messages.not_running(self.dependency_name())
            return

        try:
            self._run_stop_command()
        except subprocess.CalledProcessError as err:
            if err.output:
                raise FailedToStopError(self.dependency_name(),
                                        self.dependency_name() + ' failed to stop:\n' + err.output)
            else:
                raise FailedToStopError(self.dependency_name())

        time.sleep(.2)

        if self.status(is_stopping=True):
            raise FailedToStopError(self.dependency_name())
        else:
            print Messages.stopped(self.dependency_name())

    @abc.abstractmethod
    def dependency_name(self):
        """ Gets the name of component that will be used in status messages
        """
        raise NotImplementedError()

    @abc.abstractmethod
    def _run_status_command(self):
        """ Executes the shell command to get the component's status
        """
        raise NotImplementedError()

    @abc.abstractmethod
    def _not_running_status_code(self):
        """ Exit code from _runStatusCommand that indicates the component is not running
        """
        raise NotImplementedError()

    @abc.abstractmethod
    def _run_start_command(self):
        """ Executes the shell command that starts the component
        """
        raise NotImplementedError()

    @abc.abstractmethod
    def _run_stop_command(self):
        """ Executes the shell command that starts the component
        """
        raise NotImplementedError()


class ActiveMqManager(BaseMpfSystemDependencyManager):
    SERVICE_NAME = 'activemq'

    def __init__(self, mpf_config):
        super(ActiveMqManager, self).__init__(mpf_config)
        self._is_service = self._config.activemq_is_service

    def dependency_name(self):
        return 'ActiveMQ'

    def _run_status_command(self):
        if self._is_service:
            return self._shell.service_status(ActiveMqManager.SERVICE_NAME)
        else:
            return self._shell.check_call([self._config.active_mq, 'status'])

    def _not_running_status_code(self):
        if self._is_service:
            return ShellHelper.SERVICE_STOPPED_CODE
        else:
            return 1

    def _run_start_command(self):
        if self._is_service:
            self._shell.start_service(ActiveMqManager.SERVICE_NAME)
        else:
            self._shell.check_call([self._config.active_mq, 'start'])

    def _run_stop_command(self):
        if self._is_service:
            self._shell.stop_service(ActiveMqManager.SERVICE_NAME)
        else:
            self._shell.check_call([self._config.active_mq, 'stop'])


class MySqlManager(BaseMpfSystemDependencyManager):
    SERVICE_NAME = 'mysqld'

    def dependency_name(self):
        return 'MySQL'

    def _run_status_command(self):
        return self._shell.service_status(MySqlManager.SERVICE_NAME)

    def _not_running_status_code(self):
        return ShellHelper.SERVICE_STOPPED_CODE

    def _run_start_command(self):
        self._shell.start_service(MySqlManager.SERVICE_NAME)

    def _run_stop_command(self):
        self._shell.stop_service(MySqlManager.SERVICE_NAME)


class RedisManager(BaseMpfSystemDependencyManager):
    SERVICE_NAME = 'redis'

    def __init__(self, mpf_config):
        super(RedisManager, self).__init__(mpf_config)
        self._is_service = self._config.redis_is_service

    def dependency_name(self):
        return 'Redis'

    def _run_status_command(self):
        if self._is_service:
            return self._shell.service_status(RedisManager.SERVICE_NAME)
        else:
            return self._shell.check_call([self._config.redis_cli, 'info'])

    def _not_running_status_code(self):
        if self._is_service:
            return ShellHelper.SERVICE_STOPPED_CODE
        else:
            return 1

    def _run_start_command(self):
        if self._is_service:
            self._shell.start_service(RedisManager.SERVICE_NAME)
        else:
            self._shell.check_call(['sudo', self._config.redis_server, self._config.redis_conf])

    def _run_stop_command(self):
        if self._is_service:
            self._shell.stop_service(RedisManager.SERVICE_NAME)
        else:
            self._shell.check_call([self._config.redis_cli, 'flushall'])
            self._shell.check_call([self._config.redis_cli, 'shutdown'])


class NodeManagerManager(BaseMpfSystemDependencyManager):
    SERVICE_NAME = 'node-manager'
    RETRIES = 4
    DELAY = 5

    def dependency_name(self):
        return 'Node Manager'

    def status_string(self):
        if self._config.local_only:
            return super(NodeManagerManager, self).status_string()

        statuses = self._remote_status()
        if all(s for h, s in statuses):
            return Messages.running(self.dependency_name())
        elif all(not s for h, s in statuses):
            return Messages.not_running(self.dependency_name())

        status_msg = [Messages.some_running(self.dependency_name())]
        for host, is_running in sorted(statuses):
            if is_running:
                status_msg.append('\t' + Messages.running(host))
            else:
                status_msg.append('\t' + Messages.not_running(host))
        return '\n'.join(status_msg)

    def status(self, is_stopping=False):
        if self._config.local_only:
            return self._local_status()
        else:
            statuses = self._remote_status()
            return any(s for h, s in statuses)

    def _local_status(self):
        if self._shell.call(['service', NodeManagerManager.SERVICE_NAME, 'status']) == 0:
            return True
        try:
            self._shell.get_pid_bound_to_port(self._config.node_manager_port)
            return True
        except subprocess.CalledProcessError:
            return False

    def _remote_status(self):
        try:
            self._run_remote_ansible('status')
            return ('', True),
        except subprocess.CalledProcessError as err:
            if 'Loaded: not-found (Reason: No such file or directory)' in err.output:
                print NodeManagerManager.SERVICE_NAME, 'does not appear to be installed'
                raise
            lines = (l.split() for l in err.output.splitlines())
            statuses = ((lps[0], lps[2] == 'SUCCESS') for lps in lines)
            return tuple(statuses)

    def start(self):
        if self._config.local_only:
            super(NodeManagerManager, self).start()
            return

        statuses = self._remote_status()
        if all(s for h, s in statuses):
            print Messages.running(self.dependency_name())
            return

        try:
            self._run_start_command()
        except subprocess.CalledProcessError as err:
            if err.output:
                raise FailedToStartError(self.dependency_name(),
                                         self.dependency_name() + ' failed to start:\n' + err.output)
            else:
                raise FailedToStartError(self.dependency_name())

        time.sleep(.2)

        if all(s for h, s in self._remote_status()):
            print Messages.started(self.dependency_name())
        else:
            raise FailedToStartError(self.dependency_name())

    def _run_start_command(self):
        if self._config.local_only:
            self._shell.start_service(NodeManagerManager.SERVICE_NAME)
        else:
            self._run_remote_ansible('start')

    def _run_stop_command(self):
        if self._config.local_only:
            self._run_local_stop_command()
        else:
            self._run_remote_ansible('stop')

    def _run_local_stop_command(self):
        if self._shell.service_status(NodeManagerManager.SERVICE_NAME) == 0:
            self._shell.stop_service(NodeManagerManager.SERVICE_NAME)
            return

        # Node manager wasn't started through the service command
        pid = self._shell.get_pid_bound_to_port(self._config.node_manager_port)
        os.kill(pid, signal.SIGINT)
        for i in xrange(self.RETRIES):
            if not self._shell.process_is_running(pid):
                return
            time.sleep(self.DELAY)

    def _run_remote_ansible(self, action):
        # e.g. sudo su --login mpf --command "ansible --user mpf mpf-child --args 'service node-manager status' --become --one-line"
        remote_cmd = 'service %s %s' % (NodeManagerManager.SERVICE_NAME, action)
        ansible_cmd = "ansible --user mpf mpf-child --args '%s' --become --one-line" % remote_cmd
        return self._shell.check_output(['sudo', 'su', '--login', 'mpf', '--command', ansible_cmd])

    def _run_status_command(self):
        raise NotImplementedError()

    def _not_running_status_code(self):
        raise NotImplementedError()


class TomcatManager(BaseMpfSystemDependencyManager):
    SERVICE_NAME = 'tomcat8'

    def __init__(self, mpf_config):
        super(TomcatManager, self).__init__(mpf_config)
        self._is_service = self._config.tomcat_is_service

    def dependency_name(self):
        return 'Tomcat'

    def status(self, is_stopping=False):
        request = urllib2.Request(self._config.wfm_url)
        request.get_method = lambda: 'HEAD'
        try:
            # While Tomcat is starting or stopping this call will block until it finishes.
            # When Tomcat finishes deploying the request will receive a success response.
            # When Tomcat finishes shutting down the request will fail with ECONNRESET
            urllib2.urlopen(request)
            return True
        except urllib2.HTTPError as err:
            if err.code != 404:
                raise
            if is_stopping:
                return True
            else:
                raise FailedToStartError(self.dependency_name(),
                                         'Tomcat is running but the Workflow Manager is not.')
        except urllib2.URLError as err:
            # ECONNREFUSED occurs when Tomcat isn't running
            # ECONNRESET occurs when Tomcat shuts down
            if err.reason.errno in (errno.ECONNREFUSED, errno.ECONNRESET):
                return False
            raise
        except socket.error as err:
            if err.errno == errno.ECONNRESET:
                return False
            raise

    def _run_start_command(self):
        if self._is_service:
            self._shell.start_service(TomcatManager.SERVICE_NAME)
        else:
            self._run_with_catalina_pid([self._config.catalina, 'start'])
        time.sleep(20)

    def _run_stop_command(self):
        if self._is_service:
            self._shell.stop_service(TomcatManager.SERVICE_NAME)
        else:
            try:
                self._run_with_catalina_pid([self._config.catalina, 'stop', '120'])
            except subprocess.CalledProcessError as err:
                if err.returncode != 1:
                    raise
                self._create_pid_file()
                self._run_with_catalina_pid([self._config.catalina, 'stop', '120'])

        print 'Waiting for Node Manager to clean up...'
        time.sleep(15)

    def _run_with_catalina_pid(self, cmd_args):
        # Need to set CATALINA_PID to enable synchronous stop
        if 'CATALINA_PID' in os.environ:
            self._shell.check_call(cmd_args)
        else:
            self._shell.check_call(cmd_args, env={'CATALINA_PID': self._config.catalina_pid_file})

    def _not_running_status_code(self):
        raise NotImplementedError()

    def _run_status_command(self):
        raise NotImplementedError()

    def _get_listening_port(self):
        parse_result = urlparse.urlparse(self._config.wfm_url)
        if parse_result.port:
            return parse_result.port
        elif parse_result.scheme == 'http':
            return 80
        elif parse_result.scheme == 'https':
            return 443

    def _create_pid_file(self):
        catalina_pid_dir = os.path.dirname(self._config.catalina_pid_file)
        if not os.path.exists(catalina_pid_dir):
            os.makedirs(catalina_pid_dir)

        pid = self._shell.get_pid_bound_to_port(self._get_listening_port())
        with open(self._config.catalina_pid_file, 'w') as pidFile:
            pidFile.write(str(pid))


class MpfConfig:
    def __init__(self, verbose=False, activemq_bin=None, activemq_data=None, redis_server_bin=None, redis_cli_bin=None,
                 redis_conf=None, node_manager_port=None, catalina=None, catalina_pid_file=None,
                 workflow_manager_url=None, local_only=False, skip_activemq=False, skip_redis=False,
                 skip_node_manager=False, skip_tomcat=False, **_):

        self.verbose = verbose
        self._shell = ShellHelper(self.verbose)

        if not skip_activemq:
            self.activemq_is_service = self._shell.service_exists(ActiveMqManager.SERVICE_NAME)
            if not self.activemq_is_service:
                self.active_mq = activemq_bin
                self._verify_exists('ActiveMQ', self.active_mq, '--activemq-bin')

            if activemq_data:
                kahadb_dir = os.path.join(activemq_data, 'kahadb')
                if os.path.isdir(kahadb_dir):
                    self.kahadb_dir = kahadb_dir
                else:
                    self.kahadb_dir = None

        if not skip_redis:
            self.redis_is_service = self._shell.service_exists(RedisManager.SERVICE_NAME)
            if not self.redis_is_service:
                self.redis_server = redis_server_bin
                self._verify_exists('Redis', self.redis_server, '--redis-server-bin')

                self.redis_cli = redis_cli_bin
                self._verify_exists('Redis', self.redis_cli, '--redis-cli-bin')

                self.redis_conf = redis_conf
                self._verify_exists('Redis', self.redis_conf, '--redis-conf')

        self.node_manager_port = node_manager_port
        if not skip_node_manager:
            if local_only or not self._shell.executable_exists('ansible'):
                self.local_only = True
            else:
                self.child_nodes = self._get_child_nodes()
                self.local_only = self._only_node_manager_is_local(self.child_nodes)

        if not skip_tomcat:
            self.wfm_url = workflow_manager_url
            self.tomcat_is_service = self._shell.service_exists(TomcatManager.SERVICE_NAME)
            if not self.tomcat_is_service:
                self.catalina = catalina
                self._verify_exists('Tomcat', self.catalina, '--catalina')

                self.catalina_pid_file = catalina_pid_file
                catalina_pid_dir = os.path.dirname(self.catalina_pid_file)
                if not os.path.exists(catalina_pid_dir):
                    os.makedirs(catalina_pid_dir)

    def _verify_exists(self, component, executable, cmd_line_arg):
        if not self._shell.executable_exists(executable):
            raise UnableToFindExecutableError(component, cmd_line_arg)

    @staticmethod
    def _only_node_manager_is_local(child_nodes):
        # Running commands through ansible is much slower than running them normally,
        # so if the only host is localhost just run the command normally.
        if len(child_nodes) > 1:
            return False
        if len(child_nodes) == 0:
            return True

        local_host_name, local_aliases, _ = socket.gethostbyname_ex(socket.gethostname())
        listed_host = child_nodes[0]
        return listed_host == local_host_name or listed_host in local_aliases

    def _get_child_nodes(self):
        cmd_output = self._shell.check_output(['ansible', 'mpf-child', '--list-hosts'])
        lines = (l.strip() for l in cmd_output.splitlines() if l.strip())
        # Discard the first line because it is the host count. The rest are the hosts
        lines.next()
        return tuple(lines)


class Messages(object):
    @staticmethod
    def running(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.green('Running'))

    @staticmethod
    def not_running(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.red('Not Running'))

    @staticmethod
    def started(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.green('Started'))

    @staticmethod
    def stopped(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.red('Stopped'))

    @staticmethod
    def some_running(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.yellow('Some Running'))

    @staticmethod
    def error_during_status_check(component):
        return Messages._create_msg(component, mpf_util.MsgUtil.red('Unknown. An error occurred while checking'))

    @staticmethod
    def _create_msg(component, action):
        if len(component) < 7:
            prefix = '\t\t'
        else:
            prefix = '\t'
        return '%s:%s [  %s  ]' % (component, prefix, action)


class FailedToStartError(mpf_util.MpfError):
    def __init__(self, component, message=None):
        if message:
            super(FailedToStartError, self).__init__(message)
        else:
            super(FailedToStartError, self).__init__('%s failed to start' % component)


class FailedToStopError(mpf_util.MpfError):
    def __init__(self, component, message=None):
        if message:
            super(FailedToStopError, self).__init__(message)
        else:
            super(FailedToStopError, self).__init__('%s failed to stop' % component)


class UnableToFindExecutableError(mpf_util.MpfError):
    def __init__(self, component, cmd_line_setting):
        super(UnableToFindExecutableError, self).__init__(
            'Could not find executable for %s. Try setting %s' % (component, cmd_line_setting))


class ShellHelper:
    SERVICE_STOPPED_CODE = 3

    def __init__(self, verbose=False):
        self._verbose = verbose
        if verbose:
            self._devNull = None
            self._shellKwArgs = {}
        else:
            self._devNull = open(os.devnull, 'wb')
            self._shellKwArgs = {'stdout': self._devNull, 'stderr': self._devNull}

    def __del__(self):
        if self._devNull:
            self._devNull.close()

    def call(self, args):
        return subprocess.call(args, **self._shellKwArgs)

    def check_call(self, args, env=None):
        if env:
            sub_env = os.environ.copy()
            sub_env.update(env)
            return subprocess.check_call(args, env=sub_env, **self._shellKwArgs)
        else:
            return subprocess.check_call(args, **self._shellKwArgs)

    def check_output(self, args):
        if self._verbose:
            return subprocess.check_output(args)
        else:
            return subprocess.check_output(args, stderr=self._devNull)

    def is_on_path(self, executable):
        return self.call(['which', executable]) == 0

    def executable_exists(self, executable):
        return self.is_on_path(executable) or os.path.isfile(executable)

    def get_pid_bound_to_port(self, port):
        output = self.check_output(['sudo', 'fuser', '-n', 'tcp', str(port)])
        return int(output)

    def process_is_running(self, pid):
        return self.call(['ps', str(pid)]) == 0

    def service_exists(self, service_name):
        try:
            self.check_call(['systemctl', 'is-enabled', service_name])
            return True
        except subprocess.CalledProcessError as err:
            if err.returncode == 1:
                return False
            raise

    def service_status(self, service_name):
        return self.check_call(['service', service_name, 'status'])

    def start_service(self, service_name):
        self.check_call(['sudo', 'service', service_name, 'start'])

    def stop_service(self, service_name):
        self.check_call(['sudo', 'service', service_name, 'stop'])


sys_args = mpf_util.arg_group(
    argh.arg('-v', '--verbose', default=False, help='Show output from called commands'),

    argh.arg('--activemq-bin', default='/opt/activemq/bin/activemq',
             help='path to ActiveMQ binary'),

    argh.arg('--redis-server-bin', default='redis-server', help='path to redis-server binary'),

    argh.arg('--redis-cli-bin', default='redis-cli', help='path to redis-cli binary'),

    argh.arg('--redis-conf', default='/etc/redis.conf', help='path to redis.conf file'),

    argh.arg('--catalina', default='/opt/apache-tomcat/bin/catalina.sh',
             help='path to catalina.sh'),

    argh.arg('--node-manager-port', default=8008,
             help='port number that the Node Manager listens on'),

    argh.arg('--local-only', '-l', default=False),

    argh.arg('--workflow-manager-url', default='http://localhost:8080/workflow-manager',
             help='Url to Workflow Manager'),
    mpf_util.env_arg('--catalina-pid-file', 'CATALINA_PID', default='/tmp/mpf-script/catalina.pid'),

    argh.arg('--skip-activemq', '--xaq', default=False, help='Exclude ActiveMQ from command'),
    argh.arg('--skip-sql', '--xsql', default=False, help='Exclude SQL from command'),
    argh.arg('--skip-redis', '--xrds', default=False, help='Exclude Redis from command'),
    argh.arg('--skip-node-manager', '--xnm', default=False, help='Exclude Node Manager from command'),
    argh.arg('--skip-tomcat', '--xtc', default=False, help='Exclude Tomcat from command')
)


def filtered_start_up_order(skip_activemq, skip_sql, skip_redis, skip_node_manager, skip_tomcat,
                            **_):
    if not skip_activemq:
        yield ActiveMqManager
    if not skip_sql:
        yield MySqlManager
    if not skip_redis:
        yield RedisManager
    if not skip_node_manager:
        yield NodeManagerManager
    if not skip_tomcat:
        yield TomcatManager


@sys_args
def status(**opt_args):
    """ Displays the status of each MPF dependency """
    config = MpfConfig(**opt_args)
    for manager in filtered_start_up_order(**opt_args):
        print manager(config).status_string()


@sys_args
def start(**opt_args):
    """ Starts each MPF dependency """
    print 'Starting MPF system dependencies...'
    config = MpfConfig(**opt_args)
    for manager in filtered_start_up_order(**opt_args):
        manager(config).start()


@sys_args
def stop(**opt_args):
    """ Stops each MPF dependency """
    print 'Stopping MPF system dependencies...'
    config = MpfConfig(**opt_args)
    for manager in reversed(tuple(filtered_start_up_order(**opt_args))):
        manager(config).stop()


@sys_args
def restart(**opt_args):
    """ Stops each MPF dependency, then starts each dependency """
    stop(**opt_args)
    start(**opt_args)


COMMANDS = (status, start, stop, restart)
