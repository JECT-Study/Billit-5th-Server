window.onload = function () {
  window.ui = SwaggerUIBundle({
    url: "../openapi.yml",
    dom_id: "#swagger-ui",
    deepLinking: true,
    displayOperationId: true,
    persistAuthorization: false,
    validatorUrl: null,
  });
};
