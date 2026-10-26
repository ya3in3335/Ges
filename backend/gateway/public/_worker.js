// Pages "advanced mode" worker: forwards everything to the association-api Worker unchanged.
export default {
  fetch(request, env) {
    return env.API.fetch(request);
  },
};
