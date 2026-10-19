# Investigação do fechamento ao iniciar narração — 1.1.1

## Caso de teste

- Arquivo: `C:\Users\miche\Downloads\Competente-o-Suficiente-ebook.pdf`
- PDF válido, sem senha, 503 páginas, 35.603.679 bytes, criado por Typst 0.15.1.
- Texto extraível: aproximadamente 947.370 caracteres. A primeira página do prefácio é a página 9 do arquivo e tem cerca de 1.400 caracteres. A maior página tem 3.321 caracteres.
- Relato do usuário: o app fecha ao tocar em Play no primeiro parágrafo de conteúdo do prefácio, antes de sair áudio.

## Resultado da análise estática

O caminho do primeiro Play chama `AndroidTts.generate`, que cria um WAV pelo mecanismo de voz instalado no aparelho e o converte para MP3 pela biblioteca nativa LAME. Só depois o `PlaybackService` é configurado. Erros Java comuns nesse caminho seriam capturados e mostrados como diálogo; o fechamento direto sugere falha nativa, falta de memória ou outra exceção fora desse tratamento. **Ainda não há stack trace do aparelho para distinguir essas hipóteses.**

Havia também uma carga desnecessária no serviço: `NarrationFiles.paragraphs` lia `paragraphs.json` inteiro, inclusive todas as caixas de glifos das 503 páginas, na thread principal, quando a narração precisava apenas do índice compacto. A versão 1.1.1 passa a ler `paragraphs_index.json` de forma sequencial em worker. Isso elimina um pico previsível de memória e uma pausa da interface, mas não prova que era a causa deste fechamento, que acontece antes do áudio iniciar.

## Captura de evidências em 1.1.1

A versão 1.1.1 registra o estágio do primeiro Play (`synthesizing_wav`, `encoding_mp3` ou `mp3_ready`) e grava exceções Java não tratadas em `crash_reports/`. Na abertura seguinte, consulta os motivos de encerramento que o Android preserva: falha Java, falha nativa, ANR e memória insuficiente. Quando o sistema fornece um rastro nativo, salva também o arquivo `.pb` correspondente. Os arquivos podem ser salvos ou compartilhados em **Configurações → Relatórios de falha**.

O erro da instalação anterior poderá aparecer no histórico de encerramentos do Android, se o sistema ainda mantiver o registro. O log completo da falha já ocorrida não está disponível neste computador; não há aparelho conectado ao adb.

## Próximo teste no aparelho

1. Instalar a 1.1.1 por cima da versão atual, mantendo os dados.
2. Repetir o Play no primeiro parágrafo do prefácio.
3. Se o app fechar, reabri-lo e usar **Configurações → Relatórios de falha** para salvar ou compartilhar o `.log` mais recente e, se existir, o `.pb` de mesmo nome.

Nenhum conteúdo do livro é incluído no relatório automático; apenas identificador interno do livro, página, número e comprimento do parágrafo, estágio da síntese, dados do dispositivo e informações da falha.

## Verificação local

- `assembleDebug lintDebug`: concluídos.
- `assembleRelease lintRelease`: concluídos; APK assinado e verificado com `apksigner`.
- APK: `Rever-Android-1.1.1.apk`, versão interna 10101, Android mínimo 14.
- SHA-256: `45B5FA2CF3B2CA65371D88F9A86022E0E033F24187F24DDB70045B5A39EFA103`.
- Não houve teste de execução no aparelho, pois nenhum dispositivo estava conectado ao adb.
