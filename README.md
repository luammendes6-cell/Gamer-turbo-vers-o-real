# GAMER TURBO ANDROID — 2.3.4 (PERMISSIONS / RAM / GAME SCAN FIX)

Este projeto é um aplicativo Android nativo com interface HTML/CSS/JavaScript em uma WebView local.
A ponte JavaScript é registrada pela `MainActivity` e não depende de abrir o HTML no navegador.

## Correções 2.3.4

- A primeira solicitação de permissões executáveis ocorre depois que a WebView termina de carregar.
- O aplicativo exibe no diagnóstico os acessos de notificações, sobreposição, alteração de configurações,
  Não Perturbe, visibilidade da lista de apps e estado do Shizuku.
- O diagnóstico informa explicitamente que a leitura da RAM do sistema não exige permissão especial.
- A leitura de RAM, bateria e estado térmico agora trata falhas individualmente; uma falha no sensor de bateria
  não apaga os dados de RAM, e erros de RAM retornam uma explicação em vez de `{}`.
- Atualização da RAM continua a cada 5 segundos e também acontece quando o Android retorna à tela principal.
- A descoberta de apps usa `PackageManager`, `LauncherApps` e fallback de pacotes com atividades de abertura.
- A detecção de jogos considera categoria do Android, flag `isGame`, publicadoras/pacotes conhecidos e termos
  adicionais de jogos comuns. A lista manual continua permitindo adicionar um app que não seja classificado
  automaticamente como jogo.
- A janela de diagnóstico possui atalhos para ajustes do app, acesso à lista de apps e notificações.
- Versão do aplicativo e da ponte: `2.3.4` (versionCode 12).

## Entenda as permissões

- **RAM, bateria e estado térmico:** leitura por APIs públicas do Android; não precisam de uma permissão
  runtime do usuário.
- **Lista de aplicativos:** o APK declara `QUERY_ALL_PACKAGES`. No Android, essa autorização é tratada
  como visibilidade de pacotes declarada no manifesto, não como uma janela runtime comum. A permissão
  `com.android.permission.GET_INSTALLED_APPS` só é solicitada quando a ROM realmente a define.
- **Notificações:** Android 13 ou superior pode apresentar a autorização runtime para exibir notificações.
- **Painel flutuante:** precisa de “Mostrar sobre outros apps”, concedido em uma tela especial de Ajustes.
- **Brilho:** precisa de “Modificar configurações do sistema”, concedido em Ajustes.
- **Não Perturbe:** precisa de acesso à política de notificações.
- **CPU/GPU e comandos privilegiados:** dependem de Shizuku autorizado e de interfaces que o fabricante/kernel
  exponha como graváveis. Não há overclock, desbloqueio térmico ou garantia de alteração de frequência.

## Limitações importantes

- Nenhuma heurística consegue reconhecer automaticamente 100% dos jogos. Os apps que o Android não classifica
  como jogo continuam disponíveis no seletor de aplicativos instalados para inclusão manual.
- A leitura geral de RAM não equivale à memória exclusiva do jogo; o Android gerencia memória compartilhada,
  cache e processos em segundo plano.
- “Analisar RAM” apresenta leitura/diagnóstico. Não mata processos à força nem promete liberar memória.
- O projeto não foi testado em aparelho físico nesta sessão. A validação estática não substitui a instalação
  e o teste no Redmi/Android de destino.

## Compilação

- Abra a pasta `GamerTurbo-Android` no Android Studio ou AndroidIDE.
- Use Gradle 8.9, Android Gradle Plugin 8.7.3, Java 17 e Android SDK 35.
- Gere primeiro o APK `debug` e instale por cima da versão anterior, preservando o mesmo `applicationId`.
- Se o Android não exibir uma permissão novamente porque ela já foi negada antes, use os atalhos do diagnóstico
  para abrir os ajustes correspondentes.

Este ZIP contém o código-fonte Android, não um APK compilado.
