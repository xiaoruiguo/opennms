const angular = require('vendor/angular-js');
const elementList = require('../onms-elementList/lib/elementList');
require('../../lib/onms-pagination');
require('../../lib/onms-http');
require('angular-bootstrap-confirm');
require('angular-bootstrap-toggle/dist/angular-bootstrap-toggle');
require('angular-bootstrap-toggle/dist/angular-bootstrap-toggle.css');
require('angular-ui-router');

const indexTemplate  = require('./index.html');

(function() {
    'use strict';

    var MODULE_NAME = 'onms.daemons';

    angular.module(MODULE_NAME, [
        'angular-loading-bar',
        'ngResource',
        'ui.router',
        'ui.bootstrap',
        'ui.checkbox',
        'ui.toggle',
        'onms.http',
        'onms.elementList',
        'mwl.confirm',
        'onms.pagination'
    ])
        .config( ['$locationProvider', function ($locationProvider) {
            $locationProvider.hashPrefix('!');
            $locationProvider.html5Mode(false);
        }])
        .config(['$stateProvider', function ($stateProvider) {
            $stateProvider
                .state('daemons', {
                    url: '',
                    controller: 'ListController',
                    templateUrl: indexTemplate
                });
        }])
        .controller('ListController', ['$scope', '$http', function($scope, $http) {
            $scope.daemons = [];

            $scope.reloadPressed = function (name) {
                $http.post('rest/daemons/reload/' + name + '/');
            };

            $scope.refreshDaemonList = function () {
                    $scope.daemons = JSON.parse('[{"enabled":true,"id":20,"name":"Das_1","status":"PartiallyRunning"},{"enabled":false,"id":21,"name":"Das_2","status":"Running"},{"enabled":true,"id":22,"name":"Das_3","status":"Stopped"}]');
            };

            $scope.refreshDaemonList();

        }])
    ;
}());
