((definition) => {
    if (typeof define === "function" && define.amd) {
        define([
            "angular",
            "./pipeline-detail-dialog-service"
        ], definition);
    }
})((angular, _service) => {
    "use strict";

    function controller($scope, $mdDialog, service, pipeline) {

        $scope.onCancel = () => service.cancel($mdDialog);
        $scope.onSave = () => service.save($mdDialog);

        service.initialize($scope, pipeline);
    }

    controller.$inject = [
        "$scope", "$mdDialog", "pipeline.detail.dialog.service", "data"
    ];

    let initialized = false;
    return function init(app) {
        if (initialized) {
            return;
        }
        initialized = true;
        _service(app);
        app.controller("components.pipelines.detail.dialog", controller);
    }

});
