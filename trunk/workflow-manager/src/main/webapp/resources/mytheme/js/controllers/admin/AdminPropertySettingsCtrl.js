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

'use strict';
/* globals angular */

(function () {

var propSettingsModule = angular.module('mpf.wfm.property.settings', [
	'ngResource',
	'angular-confirm'
]);


propSettingsModule.factory('PropertiesSvc', [
'$resource',
function ($resource) {

  // // The propertyChangesAppliedResource uses the /restartRequired REST endpoint (method: GET)
  // var propertyChangesAppliedResource = $resource('restartRequired');

	// This propertiesResource.update call uses the /properties REST endpoint (method: PUT)
  // defined in AdminPropertySettingsController to save the system properties. The system properties are
  // passed as a List of Java org.mitre.mpf.mvc.model.PropertyModel objects to the mpf properties file. i.e. will save the system properties to the properties file.
	var propertiesResource = $resource('properties', {}, {
		update: {
			method: 'PUT',
			isArray: true
		}
	});

	propertiesResource.prototype.valueChanged = function () {
    return this.value !== serverProperties[this.key];
	};

  propertiesResource.prototype.needsRestart = function () {
    return this.needsRestart;
  };

  propertiesResource.prototype.resetProperty = function () {
		this.value = serverProperties[this.key];
	};

	// look for detection. prefix, these are the properties that are mutable
	var serverProperties;

	return {

    // isRestartRequired: function () {
	   //  var answer = false;
	   //  var response = propertyChangesAppliedResource.get({}).$promise.then(function(isRequired) {
	   //    answer = isRequired;
    //   });
	   //  return answer;
    // },

    // Get the list of all system properties.
    queryAll: function () {
			serverProperties = { };
			// Use the /properties REST endpoint (method: GET) defined in AdminPropertySettingsController to get all of the system properties.
      // This endpoint will return a List of org.mitre.mpf.mvc.model.PropertyModel objects.
			var properties = propertiesResource.query({whichPropertySet: "all"});
			properties
				.$promise
				.then(function () {
					properties.forEach(function (prop) {
						serverProperties[prop.key] = prop.value;
					});
				});
			return properties;
		},

    // Get the list of all mutable system properties.
    queryMutable: function () {
      serverProperties = { };
      // Use the /properties REST endpoint (method: GET) defined in AdminPropertySettingsController to get all of the mutable system properties.
      // The mutable system properties can be changed, without requiring a restart of OpenMPF to apply the change.
      var properties = propertiesResource.query({whichPropertySet: "mutable"});
      properties
      .$promise
      .then(function () {
        properties.forEach(function (prop) {
          serverProperties[prop.key] = prop.value;
        });
      });
      return properties;
    },

    // Get the list of all immutable system properties.
    queryImmutable: function () {
      serverProperties = { };
      // Use the /properties REST endpoint (method: GET) defined in AdminPropertySettingsController to get all of the immutable system properties.
      // The immutable system properties require a restart of OpenMPF to apply the change.
      var properties = propertiesResource.query({whichPropertySet: "immutable"});
      properties
      .$promise
      .then(function () {
        properties.forEach(function (prop) {
          serverProperties[prop.key] = prop.value;
        });
      });
      return properties;
    },

    update: function (properties) {

		  // Reduce the properties List of org.mitre.mpf.mvc.model.PropertyModel objects to only those that have values that have been changed, store reduced list in modifiedProps variable.
			var modifiedProps = properties.filter(function (p) {
			  return p.valueChanged();
			});

      // Save the list of modified system properties in var modifiedProps using propertiesResource.update method.
      // Note: the update method uses the /properties REST endpoint (method: PUT) defined in AdminPropertySettingsController to save the
      // modified system properties (as a List of org.mitre.mpf.mvc.model.PropertyModel objects) to the custom properties file.
			var saveResult = propertiesResource.update(modifiedProps);
			saveResult.$promise.then(function () {
       // modifiedProps.forEach(function (prop) {
        saveResult.forEach(function (prop) {
          console.log("update: processing each saveResult, prop=" + JSON.stringify(prop));
          // // Each prop is of type org.mitre.mpf.mvc.model.PropertyModel. Change to the updated value of the modified property in serverProperties.
          // // If the modified property indicates that a value change requires a restart, then prop.needsRestart will be set as needed.
          // prop.needsRestart = prop.valueChanged() && prop.needsRestartIfChanged;
          serverProperties[prop.key] = prop.value;
          // prop.needsRestart = prop.needsRestartIfChanged;
				});
			});
			return saveResult;
		},
		resetAll: function (properties) {
			properties.forEach(function (prop) {
				prop.resetProperty();
			});
		},
		unsavedPropertiesCount: function (properties) {
			var count = 0;
			properties.forEach(function (prop) {
				if (prop.valueChanged()) {
					count++;
				}
			});
			return count;
		},
		hasUnsavedProperties: function (properties) {
			return properties.some(function (prop) {
				return prop.valueChanged();
			});
		}
	};
}
]);


propSettingsModule.controller('AdminPropertySettingsCtrl', [
'$scope', '$rootScope', '$confirm', '$state', 'PropertiesSvc', 'NotificationSvc', '$q',
function ($scope, $rootScope, $confirm, $state, PropertiesSvc, NotificationSvc, $q) {

	$scope.isAdmin = $rootScope.roleInfo.admin;

  // Get the list of mutable system properties (each property in the list is of type org.mitre.mpf.mvc.model.PropertyModel).
  $scope.mutableProperties = PropertiesSvc.queryMutable();

  // Get the list of immutable system properties (each property in the list is of type org.mitre.mpf.mvc.model.PropertyModel).
  $scope.immutableProperties = PropertiesSvc.queryImmutable();

  $scope.resetAllProperties = function () {
    PropertiesSvc.resetAll($scope.mutableProperties);
    PropertiesSvc.resetAll($scope.immutableProperties);
	};

	$scope.unsavedPropertiesCount = function () {
		return PropertiesSvc.unsavedPropertiesCount($scope.mutableProperties) + PropertiesSvc.unsavedPropertiesCount($scope.immutableProperties);
	};

  $scope.hasUnsavedProperties = function () {
    return PropertiesSvc.hasUnsavedProperties($scope.mutableProperties) || PropertiesSvc.hasUnsavedProperties($scope.immutableProperties);
  };

  $scope.saveProperties = function () {

    // satisfy both promises using $q.all before providing notification of success to the user.
    $q.all([ PropertiesSvc.update($scope.mutableProperties).$promise, PropertiesSvc.update($scope.immutableProperties).$promise ])
    .then(function () {
      NotificationSvc.success('System Properties have been saved!');
    });

  };

  // $scope.isRestartRequired = PropertiesSvc.isRestartRequired();

	var confirmed = false;	// need to remember if we've asked the user the confirm, or else we'll get in loop
	$scope.$on('$stateChangeStart', function (event, toState) {
		if (confirmed || !$scope.hasUnsavedProperties()) {
			return;
		}

		event.preventDefault();	// stop the router right here, need to do this because of $confirm is asynchronous

		$confirm({
			title: "Unsaved changes",
			text: "You have modified MPF properties, but have not saved them to the server.  If you continue to another page, you will lose the changes you have made.  Are you sure you want to leave this page?",
			ok: "Yes",
			cancel: "No"
		}).then(function () {
			confirmed = true;
			$state.go(toState.name);
		});
	});
}
]);

}());