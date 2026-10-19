Scalar.createApiReference("#app", {
  content: JSON.parse(document.getElementById("openapi-spec").textContent),
  theme: "solarized",
  agent: { disabled: true },
  showDeveloperTools: "never",
  persistAuth: false,
  telemetry: false,
  withDefaultFonts: false,
});
