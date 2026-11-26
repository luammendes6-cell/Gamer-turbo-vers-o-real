# Gamer Turbo Android 2.6.4

Aplicativo Android com interface landscape, biblioteca de jogos, painel flutuante nativo e ferramentas de partida.

## Novidades 2.6.4
- Abertura em três fases: sistema em repouso, ignição e Gamer Turbo online.
- Efeito sonoro original sintetizado localmente, acionado por toque e sincronizado com a sequência visual; não usa gravação da BMW.
- Entrada do DIABLO com revelação progressiva dos painéis, ignição do núcleo e apresentação em sequência das leituras e recursos.
- Blocos CSS reposicionados dentro do cabeçalho HTML para corrigir a estrutura do documento.
- Identificação Android/ponte atualizada para 2.6.4.

## Principais recursos
- Modos ECO, BALANCE, RISE e DIABLO, aplicados somente quando o kernel/Android permitem.
- Painel flutuante em formato de toolbox, com limite de tamanho.
- Monitoramento de bateria, RAM, CPU global, frequência de CPU, FPS SurfaceFlinger, temperatura da bateria e estado térmico.
- Ações: limpar RAM, fechar apps, prioridade e boost.
- Captura, gravação, Não Perturbe, controle de brilho, painel Wi-Fi, temporizador, atalhos de armazenamento/Cast e exceções de memória.
- Biblioteca de jogos com cartões compactos; nome, pacote e botão ABRIR permanecem em áreas separadas, sem sobreposição.
- Painel CPU com controles táteis para os limites mínimo/máximo da faixa de fábrica CPUFreq, leitura/escrita verificada e aviso explícito quando o kernel é somente leitura.
- Hibernação no DIABLO: encerra com force-stop aplicativos de terceiros em segundo plano, preservando apps do sistema, jogo e exceções.
- Janelas de Exceções e CPU com botão de fechar no cabeçalho, rolagem interna e cancelamento de leituras pendentes ao fechar.
- Resultados das ações resumidos em mensagens e relatórios: aplicado, aplicado parcialmente, bloqueado ou não confirmado.

## Requisitos e permissões
- Android 7.0/API 24 ou superior.
- Android SDK e Gradle 8.9 para compilação.
- Acesso de sobreposição para o painel flutuante.
- Shizuku iniciado e autorizado para recursos via shell, incluindo leitura de FPS quando a ROM expõe a camada no SurfaceFlinger.
- Acesso Não Perturbe para FOCO; acesso de modificar configurações para BRILHO.

## Limitações
O monitor de CPU é global, não por processo. A frequência mostrada em GHz é uma média ponderada das políticas CPUFreq disponíveis e pode não representar a frequência instantânea de cada núcleo. O controle de mínimo/máximo não é overclock: respeita cpuinfo_min_freq/cpuinfo_max_freq e depende de escrita permitida pelo kernel via Shizuku. O FPS é amostrado através do SurfaceFlinger e pode ficar indisponível em determinadas ROMs. Perfis de toque, áudio, interpolação de quadros, Gyroboost, voice changer e controles avançados de GPU são recursos de integração OEM/driver da Xiaomi e não podem ser reproduzidos fielmente por um app Android genérico. O projeto não apresenta esses recursos como funcionais quando o Android não fornece uma API.

Consulte `AUDITORIA-2.6.4.txt` para o inventário de alterações, validações estáticas e limitações de compilação/teste neste ambiente.
OBSERVAÇÃO DE COMPILAÇÃO: o pacote-fonte anterior não incluía gradle-wrapper.jar. O workflow do GitHub Actions desta versão instala JDK 17 e Gradle 8.9 diretamente. Para compilar localmente, use uma instalação Gradle 8.9 ou gere o wrapper a partir de uma instalação Gradle confiável.
