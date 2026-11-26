# Gamer Turbo Android 2.6.0

Aplicativo Android com interface landscape, biblioteca de jogos, painel flutuante nativo e ferramentas de partida.

## Principais recursos
- Modos ECO, BALANCE, RISE e DIABLO, aplicados somente quando o kernel/Android permitem.
- Painel flutuante em formato de toolbox, com limite de tamanho.
- Monitoramento de bateria, RAM, CPU global, frequência de CPU, FPS SurfaceFlinger, temperatura da bateria e estado térmico.
- Ações: limpar RAM, fechar apps, prioridade e boost.
- Captura, gravação, Não Perturbe, controle de brilho, painel Wi-Fi, temporizador, atalhos de armazenamento/Cast e exceções de memória.
- Biblioteca de jogos com cartões compactos e rolagem horizontal.
- Painel CPU para definir limites mínimo/máximo por percentual da faixa de fábrica disponível no CPUFreq. Os valores são guardados e restaurados no fim da sessão.
- Hibernação no DIABLO: encerra com force-stop aplicativos de terceiros em segundo plano, preservando apps do sistema, jogo e exceções.
- Encerramento automático do serviço flutuante quando a partida termina ou a tela inicial do Gamer Turbo sai de foco sem iniciar um jogo.

## Requisitos e permissões
- Android 7.0/API 24 ou superior.
- Android SDK e Gradle 8.9 para compilação.
- Acesso de sobreposição para o painel flutuante.
- Shizuku iniciado e autorizado para recursos via shell, incluindo leitura de FPS quando a ROM expõe a camada no SurfaceFlinger.
- Acesso Não Perturbe para FOCO; acesso de modificar configurações para BRILHO.

## Limitações
O monitor de CPU é global, não por processo. A frequência mostrada em GHz é uma média ponderada das políticas CPUFreq disponíveis e pode não representar a frequência instantânea de cada núcleo. O controle de mínimo/máximo não é overclock: respeita cpuinfo_min_freq/cpuinfo_max_freq e depende de escrita permitida pelo kernel via Shizuku. O FPS é amostrado através do SurfaceFlinger e pode ficar indisponível em determinadas ROMs. Perfis de toque, áudio, interpolação de quadros, Gyroboost, voice changer e controles avançados de GPU são recursos de integração OEM/driver da Xiaomi e não podem ser reproduzidos fielmente por um app Android genérico. O projeto não apresenta esses recursos como funcionais quando o Android não fornece uma API.

Consulte `AUDITORIA-2.6.0.txt` para o inventário de alterações e limitações técnicas.
