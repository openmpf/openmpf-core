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

'use strict';


//'ngAnimate',
var App = angular.module('mpf.wfm', [
	'ui.router',
	'ui.bootstrap',
	'ui.select',
	'ui.grid',
	'ngSanitize',
	'ngMessages',
	'ngResource',
	'as.sortable',
	'angular-confirm',
	'mpf.wfm.controller.ServerMediaCtrl',
	'mpf.wfm.controller.AdminComponentRegistrationCtrl',
	'mpf.wfm.pipeline2',
	'mpf.wfm.filters',
	'mpf.wfm.services',
	'mpf.wfm.directives',
	'mpf.wfm.property.settings'
]);

// Declare app level module which depends on filters, and services
App.config(['$stateProvider', '$urlRouterProvider','$httpProvider' ,function ($stateProvider, $urlRouterProvider,$httpProvider) {
	  // For any unmatched url, redirect to /jobs
	  $urlRouterProvider.otherwise("/jobs");

	  $stateProvider.state('/about', {
		  url: '/about',
		  templateUrl: 'about/layout.html',
		  controller: AboutCtrl,
		  resolve: {
            depResponse: function($http) {
                return $http.get("resources/json/dependencies.json");
            }
		  }
	  });

	  $stateProvider.state('/server_media', {
		  url: '/server_media',
		  templateUrl: 'server_media/layout.html',
		  controller: 'ServerMediaCtrl'
	  });

	  $stateProvider.state('/pipelines', {
		  url: '/pipelines',
		  templateUrl: 'pipelines/layout',
		  controller: PipelinesCtrl
	  });

	  $stateProvider.state('/pipelines2', {
		  url: '/pipelines2',
		  templateUrl: 'pipelines2/layout.html',
          controller: 'Pipelines2Ctrl',
          // controllerAs: 'pipes2',
          // resolve: {
          //     init: function() {
          //         vm.init();
          //     }
          // }
      });

	  $stateProvider.state('/jobs', {
		  url: '/jobs',
		  templateUrl: 'jobs/layout.html',
		  controller: JobsCtrl
	  });

	  $stateProvider.state('/adminNodes', {
		  url: '/adminNodes',
		  templateUrl: 'admin/nodes/layout',
		  controller: AdminNodesCtrl,
		  resolve: {
			  checkDocker: ['MetadataService', '$state', function (MetadataService, $state) {
				  return MetadataService.getMetadata()
					  .then(function (metadata) {
						  if (!metadata.dockerEnabled) {
						      return;
						  }
						  if (!$state.current.name) {
							  // Only send user to /jobs when they aren't currently on one of our pages.
							  $state.go("/jobs");
						  }
						  // Throw error to prevent user from navigating to this page.
						  throw new Error("The adminNodes page is disabled in Docker deployments.");
					  });
			  }] // checkDocker
		  }
	  });

	  $stateProvider.state('/admin/propertySettings', {
		  url: '/admin/propertySettings',
		  templateUrl: 'admin/property_settings/layout',
		  controller: 'AdminPropertySettingsCtrl'
	  });

	  $stateProvider.state('/admin/componentRegistration', {
		  url: '/admin/componentRegistration',
		  templateUrl: 'admin/component_registration/layout',
		  controller: 'AdminComponentRegistrationCtrl',
		  resolve: {
			  roleInfo: ['RoleService', function (r) { return r.getRoleInfo(); }],
			  metadata: ['MetadataService', function (m) { return  m.getMetadata(); }]
		  }
	  });

	  $stateProvider.state('/adminLogs', {
		  url: '/adminLogs',
		  templateUrl: 'admin/log/layout' ,
		  controller: AdminLogsCtrl
	  });

	  $stateProvider.state('/adminStatistics', {
		  url: '/adminStatistics',
		  templateUrl: 'admin/stats/layout',
		  controller: AdminStatsCtrl
	  });

      $stateProvider.state('/adminWebServices', {
          url: '/adminWebServices',
          templateUrl: 'adminWebServices/layout.html' //,
          // controller: //TODO: need a controller
      });

	$httpProvider.defaults.headers.common["X-Requested-With"] = 'XMLHttpRequest';//need to add explicitly but can cause problems with CORS
    $httpProvider.interceptors.push('httpInterceptor');//services.js
}]);

//$log.debug should not work without this, but other levels should
App.config(function($logProvider){
	$logProvider.debugEnabled(true);
});


//run startup code to redirect to the start page if this is an admin logic
App.run( function( $rootScope, $state, $log, $interval, RoleService, MetadataService, ServerSidePush, SystemStatus,ClientState ,NodeService) {
	$log.debug('WfmAngularSpringApp starting.');

	/* this is the data structure that the system-notice directive uses to store system notices */
	$rootScope.systemNotices = [];

	/* this keeps track of the last time this client received a message from the server or successfully sent a request to the server
	 * currently only used by Atmosphere, but should be extended (via decorators: http://solutionoptimist.com/2013/10/07/enhance-angularjs-logging-using-decorators/)
	 * to any server-side exchange.
	 */
	$rootScope.lastServerExchangeTimestamp = moment();	// initialize to current time because we must have just gotten this file


	/* get initial System Messages */
	$rootScope.$watch('$viewContentLoaded', function(){
		//$log.debug("app.run: about to show all system messages");
		SystemStatus.showAllSystemMessages();
	});

	//check every minute to see if the user should be booted out
	//using get metadata as the polling target
	// replaced $timeout since it interferes with protractor, and this is equivalent
	//	however, this should be replaced by something more elegant, perhaps with server side push
	$interval( function() {
		MetadataService.getMetadataNoCache();
	}, 60000);
} );
