module.exports = function (api) {
  api.cache(true);

  return {
    presets: [["babel-preset-expo", { reanimated: false, worklets: false }]],
    plugins: [["react-native-reanimated/plugin", { processNestedWorklets: true }]],
  };
};
