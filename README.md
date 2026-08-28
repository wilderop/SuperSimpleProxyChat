# Super Simple Proxy Chat

Velocity plugin for the LawlessMC network.

- Relays chat to players on **other backends** (same-server chat stays vanilla)
- Custom MiniMessage nicks from BackChatHelper
- `/ignore` sync from backends
- Network `/msg`: deliver live if the target is online anywhere, otherwise queue until they join

## Install

1. Build: `mvn clean package`
2. Put `target/SuperSimpleProxyChat.jar` in the Velocity `plugins/` folder
3. Install [BackChatHelper](https://github.com/wilderop/BackChatHelper) on every Paper backend

## Config

`plugins/supersimpleproxychat/config.yml`

```yaml
chat-format: "<{nick}> {message}"
```

Placeholders: `{player}`, `{nick}`, `{message}`. MiniMessage tags are supported.

## Notes

- Chat is **not cancelled** on the proxy, so signed 1.19.1+ clients are not kicked.
- Offline mail only works for names the proxy has seen at least once (`known-names.txt`).
- Does not replace a Discord plugin. It only controls in-game / proxy chat.
