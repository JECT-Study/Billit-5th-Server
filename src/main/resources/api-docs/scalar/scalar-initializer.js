Scalar.createApiReference("#app", {
  url: new URL("/api-docs/openapi.yml", window.location.href).href,
  operationTitleSource: "summary",
  operationsSorter: "alpha",
  theme: "solarized",
  agent: { disabled: true },
  showDeveloperTools: "never",
  persistAuth: false,
  telemetry: false,
  withDefaultFonts: false,
});
