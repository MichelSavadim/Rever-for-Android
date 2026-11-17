# Rever

O Rever é um leitor de PDF com narração local pelo mecanismo de voz instalado no Android. Ele gera MP3 para cada parágrafo durante a reprodução e também permite exportar o áudio do PDF inteiro ou de um intervalo de páginas.

Opções de desenvolvedor foram adicionadas para uso pessoal.

Em **Opções de desenvolvedor**, a sincronização permite escolher separadamente PDFs, pontos de leitura, estatísticas e exceções de áudio. Uma opção desligada impede o envio e o recebimento daquele dado no aparelho; a troca acontece quando ambos os aparelhos a mantêm ligada.

Na versão 1.1.0, **Configurações → Dados e Armazenamento** permite escolher a pasta principal, criar ou restaurar backups, ajustar o backup automático e limpar o cache. Backups automáticos são salvos em Downloads/Rever/Backups. **Estatísticas** mostra o tempo de leitura e narração, livros e páginas acessados. As opções de idioma e voz ficam em **Narração**.

## Biblioteca

Toque em **+ Adicionar PDF** para importar um arquivo. PDFs com texto selecionável são aceitos; o suporte a documentos digitalizados ainda depende de OCR. Segure um PDF para selecioná-lo. Enquanto há uma seleção, você pode abrir, renomear, compartilhar, apagar ou atribuir categorias. Toque em outros PDFs para selecioná-los em conjunto.

Se houver categorias, a faixa horizontal abaixo do título da biblioteca permite alternar entre elas. **Padrão** contém os PDFs sem categoria. Em **Configurações → Categorias**, você pode criar, editar, excluir e reordenar as categorias. Um PDF pode pertencer a várias categorias.

## Leitura e áudio

Deslize o PDF para ler, use pinça para ampliar e toque em um parágrafo para selecioná-lo. A cápsula lateral permite digitar uma página; a bolinha ao lado pode ser arrastada para percorrer o livro. O controle desaparece após alguns segundos sem movimento.

Depois de selecionar um parágrafo, abra o painel direito e toque em **Exceção** para excluí-lo da narração. É possível incluir também os trechos que contenham o mesmo texto ou apareçam na mesma posição das páginas. Em **Lista de Exceções**, o X libera um parágrafo específico.

O player reproduz os parágrafos em ordem, mesmo com a tela bloqueada. Os controles permitem pausar, mudar a velocidade, saltar 10 segundos e avançar ou voltar por parágrafo e por página. O painel direito oferece exportação de MP3 completo ou por intervalo.

## Atualizações

O Rever consulta as versões publicadas no repositório ao ser aberto. Quando há uma versão nova, você pode atualizar ou pular seus avisos. Uma versão pulada continua em **Configurações → Mensagens** para atualização posterior. O download ocorre no app; a instalação é confirmada no instalador do Android. Em **Sobre**, é possível procurar atualizações, ver o changelog e abrir o repositório.

Os PDFs, áudios e dados de leitura ficam na área interna do aplicativo e são apagados ao desinstalá-lo. Salve exportações importantes fora do Rever.


## Pronúncia

Abra um PDF e toque em **Pronúncia** no painel dele. A tela mostra apenas as listas próprias ou herdadas desse PDF. Uma nova lista já fica associada ao PDF aberto. Cadastre uma regra por linha, como `hardware = rád uér`. Opcionalmente, marque categorias para compartilhar essa mesma lista e suas futuras alterações com os outros PDFs. Também é possível editar ou excluir a lista. As categorias são reconhecidas pelo mesmo nome nos dois aparelhos. As trocas respeitam palavras inteiras, ignoram maiúsculas/minúsculas e afetam somente o texto enviado à voz. Áudios antigos não são reutilizados quando as regras efetivas mudam; um trecho que já está tocando ou preparado pode terminar antes da mudança. Em Opções de desenvolvedor, **Pronúncia** habilita envio e recebimento quando ligada em ambos os aparelhos. Listas diferentes são mescladas; edições simultâneas na mesma lista usam a versão mais recente. PDFs e categorias continuam sendo administrados em cada biblioteca.
