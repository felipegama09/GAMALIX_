# GamaFlix — app para Android TV

Player IPTV nativo (Kotlin + Media3/ExoPlayer) com **TV ao vivo, Filmes e Séries**.
Aceita **Xtream Codes** (servidor + usuário + senha) e **lista M3U** (link).

## Como usar
- **Login:** escolha Xtream Codes ou Lista M3U e preencha os dados (fica salvo; "Sair" apaga).
- **Navegação:** abas TV / Filmes / Séries no topo, categorias à esquerda, grade à direita. "Buscar" filtra pelo nome.
- **TV ao vivo:** ↑ / ↓ (ou CH+/CH−) trocam de canal; OK mostra o nome do canal.
- **Filmes / Séries:** controles padrão de player (OK pausa, ← → avançam). Série abre a lista de episódios e passa para o próximo sozinha.
- **Leve para TV de 2 GB:** buffers pequenos, cache de imagens limitado, imagens em RGB_565, sem animações próprias, listas virtualizadas (RecyclerView).

## Gerar o APK (escolha UMA opção)

### A) Android Studio (mais fácil, Windows)
1. Instale o Android Studio. 2. `File > Open` e escolha esta pasta. 3. Aguarde o Gradle sincronizar.
4. `Build > Generate App Bundles or APKs > Generate APKs`, e depois `Build Variants > release` se preferir a versão otimizada.

### B) Linha de comando (precisa de JDK 17+ e Android SDK)
```
./gradlew assembleRelease        (Windows: gradlew.bat assembleRelease)
```
O APK sai em `app/build/outputs/apk/release/app-release.apk` (assinado com a chave de debug, instalável por ADB).

### C) Nuvem, sem instalar nada
Envie esta pasta para um repositório no GitHub; em `Actions > Build APK > Run workflow`, baixe o artefato `GamaFlix-apk`.

## Instalar na TV
```
adb connect 192.168.18.5:5555
adb -s 192.168.18.5:5555 install -r app-release.apk
```
O app aparece na lista de aplicativos (e no Projectivy/Home) como **GamaFlix**.
Compatível com Android 5.0+ (API 21+), serve para a sua Philco (Android 11, armeabi-v7a).

## Observações
- Este projeto foi escrito, mas **ainda não foi compilado nem testado em TV**; o ambiente onde foi criado não tinha acesso ao Android SDK. Se o build acusar algum erro, me envie a mensagem que eu corrijo.
- Use apenas listas IPTV às quais você tem direito de acesso.
- Canais Xtream usam `.m3u8` (HLS) e, se falhar, o app tenta `.ts` automaticamente.
