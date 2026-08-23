exports.handler = async (event) => {
  return {
    statusCode: 200,
    headers: { "Content-Type": "text/html" },
    body: "<!DOCTYPE html><html><head><title>Coolture</title></head><body><h1>SSR Placeholder</h1></body></html>",
  };
};
