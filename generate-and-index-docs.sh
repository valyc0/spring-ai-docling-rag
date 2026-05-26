#!/usr/bin/env bash
# Genera 10 file HTML su argomenti diversi e li indicizza via POST /docling/parse

set -e
BASE_URL="${BASE_URL:-http://localhost:8080}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/esempi/test-docs"
mkdir -p "$OUT_DIR"

# ── Helper per generare un file HTML ─────────────────────────────────────────
make_html() {
  local file="$1"; local title="$2"; shift 2
  {
    echo "<!DOCTYPE html><html lang=\"it\"><head><meta charset=\"UTF-8\"><title>$title</title></head><body>"
    echo "<h1>$title</h1>"
    while (( "$#" >= 2 )); do
      echo "<h2>$1</h2><p>$2</p>"
      shift 2
    done
    echo "</body></html>"
  } > "$file"
}

# ── 10 documenti ─────────────────────────────────────────────────────────────
make_html "$OUT_DIR/01_intelligenza-artificiale.html" \
  "Introduzione all'Intelligenza Artificiale" \
  "Cos'è l'IA" "L'intelligenza artificiale è un ramo dell'informatica che studia la simulazione di processi cognitivi umani da parte di macchine, in particolare sistemi informatici. Comprende l'apprendimento automatico, il ragionamento e l'auto-correzione." \
  "Machine Learning" "Il machine learning è un sottoinsieme dell'IA che consente ai sistemi di apprendere automaticamente dai dati e migliorare dall'esperienza senza essere esplicitamente programmati. Gli algoritmi costruiscono modelli matematici basati su dati campione." \
  "Deep Learning" "Il deep learning utilizza reti neurali artificiali con molti strati per modellare astrazioni di alto livello nei dati. È alla base di applicazioni come il riconoscimento vocale, la visione artificiale e la traduzione automatica." \
  "Applicazioni pratiche" "L'IA viene applicata in numerosi settori: diagnostica medica, guida autonoma, assistenti virtuali, raccomandazione di contenuti, rilevamento frodi e ottimizzazione logistica."

make_html "$OUT_DIR/02_cambiamenti-climatici.html" \
  "Cambiamenti Climatici e Ambiente" \
  "Cause del riscaldamento globale" "Il riscaldamento globale è principalmente causato dall'aumento delle emissioni di gas serra, in particolare CO₂ e metano, derivanti dalla combustione di combustibili fossili, dalla deforestazione e dalle attività agricole intensive." \
  "Effetti sul pianeta" "Gli effetti includono lo scioglimento dei ghiacciai, l'innalzamento del livello del mare, eventi meteorologici estremi più frequenti, alterazione degli ecosistemi e migrazioni di specie. Le temperature medie globali sono aumentate di circa 1,2°C rispetto all'era preindustriale." \
  "Accordi internazionali" "L'Accordo di Parigi del 2015 impegna i paesi firmatari a limitare il riscaldamento globale a 1,5-2°C rispetto ai livelli preindustriali. Richiede riduzioni significative delle emissioni entro il 2030 e la neutralità carbonica entro il 2050." \
  "Soluzioni tecnologiche" "Le principali soluzioni comprendono le energie rinnovabili (solare, eolico, idroelettrico), l'efficienza energetica negli edifici e nei trasporti, la cattura e lo stoccaggio del carbonio e l'economia circolare."

make_html "$OUT_DIR/03_storia-romana.html" \
  "Storia dell'Impero Romano" \
  "La Repubblica Romana" "La Repubblica Romana nacque nel 509 a.C. dopo la cacciata del re Tarquinio il Superbo. Era governata da due consoli eletti annualmente, dal Senato e da varie assemblee popolari. Espanse progressivamente il controllo sull'Italia peninsulare." \
  "Giulio Cesare e la fine della Repubblica" "Giulio Cesare, dopo aver conquistato la Gallia, attraversò il Rubicone nel 49 a.C. scatenando una guerra civile. Divenne dittatore perpetuo ma fu assassinato alle Idi di Marzo del 44 a.C. da un gruppo di senatori guidati da Bruto e Cassio." \
  "L'età augustea" "Augusto, nipote adottivo di Cesare, divenne il primo imperatore romano nel 27 a.C. Il suo principato inaugurò una lunga era di pace e prosperità, la Pax Romana. Promosse le arti, la letteratura e grandi opere architettoniche in tutto l'impero." \
  "La caduta dell'Impero" "L'Impero Romano d'Occidente cadde nel 476 d.C. quando Odoacre depose l'ultimo imperatore Romolo Augustolo. Le cause furono molteplici: crisi economica, pressioni barbariche, problemi militari e instabilità politica interna."

make_html "$OUT_DIR/04_blockchain.html" \
  "Blockchain e Criptovalute" \
  "Come funziona la blockchain" "La blockchain è un registro distribuito e immutabile di transazioni organizzate in blocchi concatenati crittograficamente. Ogni blocco contiene un hash del blocco precedente, garantendo l'integrità della catena. La decentralizzazione elimina la necessità di un'autorità centrale." \
  "Bitcoin ed Ethereum" "Bitcoin, creato nel 2009 da Satoshi Nakamoto, è la prima criptovaluta basata su blockchain. Ethereum, lanciato nel 2015, ha introdotto gli smart contract: programmi auto-eseguibili che si attivano al verificarsi di condizioni predeterminate, abilitando applicazioni decentralizzate." \
  "Smart Contract e DeFi" "La finanza decentralizzata (DeFi) utilizza smart contract per replicare servizi finanziari tradizionali senza intermediari: prestiti, scambi, derivati e rendimenti. Il valore totale bloccato nei protocolli DeFi ha raggiunto centinaia di miliardi di dollari." \
  "Rischi e regolamentazione" "I principali rischi includono alta volatilità, rischi di sicurezza degli smart contract, frodi e impatto ambientale del mining. I regolatori di tutto il mondo stanno sviluppando framework normativi per le criptovalute, con approcci molto diversi tra loro."

make_html "$OUT_DIR/05_alimentazione.html" \
  "Nutrizione e Alimentazione Sana" \
  "Macronutrienti" "I macronutrienti sono carboidrati, proteine e grassi. I carboidrati forniscono energia rapida, le proteine costruiscono e riparano i tessuti, i grassi sono essenziali per le funzioni cellulari e l'assorbimento delle vitamine liposolubili. Un equilibrio tra questi è fondamentale per la salute." \
  "Dieta mediterranea" "La dieta mediterranea è riconosciuta dall'UNESCO come patrimonio culturale immateriale. Caratterizzata da elevato consumo di frutta, verdura, legumi, cereali integrali, olio d'oliva e pesce, con moderato consumo di carni bianche e ridotto consumo di carni rosse e dolci." \
  "Micronutrienti essenziali" "Vitamine e minerali sono essenziali per il funzionamento dell'organismo. La vitamina D è cruciale per le ossa e il sistema immunitario; il ferro trasporta l'ossigeno nel sangue; il calcio mantiene ossa e denti; gli omega-3 proteggono il sistema cardiovascolare." \
  "Alimentazione e malattie croniche" "Una dieta non equilibrata è associata a obesità, diabete tipo 2, malattie cardiovascolari e alcuni tipi di cancro. Ridurre zuccheri aggiunti, grassi saturi, sale e alimenti ultra-processati riduce significativamente il rischio di sviluppare queste patologie."

make_html "$OUT_DIR/06_musica-classica.html" \
  "Storia della Musica Classica" \
  "Il Barocco" "Il periodo barocco (1600-1750) è caratterizzato da ornamentazione elaborata, contrappunto e basso continuo. I compositori principali includono Johann Sebastian Bach, Georg Friedrich Handel e Antonio Vivaldi. La musica esprimeva emozioni forti attraverso contrasti dinamici." \
  "Il Classicismo" "L'era classica (1750-1820) privilegia chiarezza, equilibrio e forma. Franz Joseph Haydn standardizzò la forma sonata e la sinfonia classica. Wolfgang Amadeus Mozart eccelse in ogni genere musicale, dalla sinfonia all'opera. Ludwig van Beethoven fece da ponte verso il Romanticismo." \
  "Il Romanticismo" "Il Romanticismo musicale (1820-1900) enfatizza l'espressione emotiva e l'individualismo. Franz Schubert, Frédéric Chopin, Robert Schumann, Johannes Brahms e Pyotr Ilyich Tchaikovsky esplorarono nuovi linguaggi armonici e forme espressive più libere e personali." \
  "Il Novecento" "Il XX secolo vide la dissoluzione della tonalità tradizionale. Arnold Schoenberg introdusse la dodecafonia; Igor Stravinsky rivoluzionò il ritmo con La Sagra della Primavera; Béla Bartók integrò elementi folk. Emersero minimalismo, musica concreta e sperimentalismo elettronico."

make_html "$OUT_DIR/07_spazio.html" \
  "Esplorazione Spaziale" \
  "La corsa allo spazio" "La corsa allo spazio fu una competizione tecnologica tra USA e URSS durante la Guerra Fredda. L'URSS lanciò il primo satellite (Sputnik, 1957) e il primo cosmonauta (Gagarin, 1961). Gli USA risposero con il programma Apollo, portando il primo uomo sulla Luna nel 1969." \
  "La Stazione Spaziale Internazionale" "La ISS è un laboratorio scientifico orbitante gestito da una collaborazione internazionale. Ospita continuamente astronauti dal 2000 e ha condotto migliaia di esperimenti in microgravità in campi quali medicina, biologia, fisica e scienze dei materiali." \
  "Missioni su Marte" "Diversi rover hanno esplorato Marte: Curiosity (2012) e Perseverance (2021) hanno confermato la presenza passata di acqua liquida e analizzato la composizione del suolo. Perseverance ha anche prodotto ossigeno dall'atmosfera marziana e trasportato il mini-elicottero Ingenuity." \
  "Turismo spaziale e futuro" "Aziende private come SpaceX, Blue Origin e Virgin Galactic stanno rendendo lo spazio accessibile ai civili. SpaceX punta alla colonizzazione di Marte con la Starship. L'ESA e la NASA pianificano un ritorno umano sulla Luna attraverso il programma Artemis entro il 2026."

make_html "$OUT_DIR/08_diritto-contratti.html" \
  "Diritto dei Contratti" \
  "Elementi essenziali del contratto" "Un contratto valido richiede: accordo tra le parti (proposta e accettazione), causa lecita, oggetto determinato o determinabile, e la capacità giuridica delle parti. In alcuni casi è richiesta una forma specifica, come la forma scritta per i contratti immobiliari." \
  "Vizi del consenso" "Il consenso può essere viziato da errore, dolo o violenza. L'errore deve essere essenziale e riconoscibile dall'altra parte. Il dolo implica l'uso di inganni per indurre alla stipula. La violenza comprende sia la coercizione fisica che la minaccia di un danno ingiusto e grave." \
  "Inadempimento e rimedi" "In caso di inadempimento, il creditore può richiedere l'esecuzione forzata, la risoluzione del contratto o il risarcimento del danno. Il danno risarcibile comprende il danno emergente (perdita subita) e il lucro cessante (mancato guadagno), purché prevedibili al momento della stipula." \
  "Contratti digitali e B2B" "I contratti elettronici hanno piena validità giuridica se rispettano i requisiti formali. Nei contratti B2B (business-to-business) vige maggiormente la libertà contrattuale rispetto ai contratti con consumatori, dove si applicano tutele specifiche contro le clausole abusive."

make_html "$OUT_DIR/09_energia-rinnovabile.html" \
  "Energia Rinnovabile" \
  "Energia solare fotovoltaica" "I pannelli fotovoltaici convertono la luce solare direttamente in elettricità tramite l'effetto fotoelettrico. Il costo dell'energia solare è diminuito del 90% nell'ultimo decennio, rendendola la fonte energetica più economica in molte regioni. La capacità installata globale supera i 1.000 GW." \
  "Energia eolica" "Le turbine eoliche convertono l'energia cinetica del vento in elettricità. L'eolico offshore è particolarmente promettente per la maggiore costanza dei venti marini. La Danimarca produce oltre il 50% della sua elettricità dal vento, dimostrando la fattibilità di un'economia eolica." \
  "Accumulo energetico" "Le batterie agli ioni di litio sono la tecnologia dominante per l'accumulo su piccola e media scala. Per la rete elettrica si studiano accumuli a pompaggio idroelettrico, batterie a flusso, idrogeno verde e supercapacitori. L'accumulo è la chiave per risolvere l'intermittenza delle rinnovabili." \
  "Idrogeno verde" "L'idrogeno verde è prodotto tramite elettrolisi dell'acqua usando energia rinnovabile. È un vettore energetico versatile utilizzabile in settori difficili da elettrificare: siderurgia, chimica, aviazione e trasporto pesante. Il costo di produzione è in forte calo grazie alle economie di scala."

make_html "$OUT_DIR/10_psicologia-cognitiva.html" \
  "Psicologia Cognitiva" \
  "Percezione e attenzione" "La percezione è il processo con cui il cervello interpreta le informazioni sensoriali. L'attenzione è selettiva: il cervello filtra milioni di stimoli per concentrarsi su ciò che è rilevante. Fenomeni come la cecità al cambiamento e l'attenzione selettiva dimostrano i limiti della nostra consapevolezza percettiva." \
  "Memoria e apprendimento" "La memoria si divide in sensoriale (millisecondi), di lavoro (7±2 elementi) e a lungo termine (dichiarativa e procedurale). L'apprendimento avviene attraverso la ripetizione spaziata, la codifica elaborativa e il recupero attivo delle informazioni, strategie molto più efficaci della semplice rilettura." \
  "Euristiche e bias cognitivi" "Il cervello usa scorciatoie cognitive (euristiche) per prendere decisioni rapide. Questi meccanismi introducono errori sistematici chiamati bias: il bias di conferma porta a cercare informazioni che confermano le credenze esistenti; l'ancoraggio ci rende dipendenti dalla prima informazione ricevuta." \
  "Intelligenza emotiva" "L'intelligenza emotiva (IE) è la capacità di riconoscere, comprendere e gestire le proprie emozioni e quelle altrui. Comprende consapevolezza di sé, autoregolazione, motivazione, empatia e abilità sociali. Studi mostrano che la IE predice il successo professionale meglio del QI tradizionale."

echo "Generati 10 file HTML in $OUT_DIR"
echo ""

# ── Indicizzazione ────────────────────────────────────────────────────────────
SUCCESS=0
FAIL=0
for f in "$OUT_DIR"/*.html; do
  name="$(basename "$f")"
  echo -n "Indicizzazione: $name ... "
  HTTP_CODE=$(curl -s -o /tmp/parse_response.json -w "%{http_code}" \
    -X POST "$BASE_URL/docling/parse" \
    -F "file=@$f;type=text/html")
  if [[ "$HTTP_CODE" == "200" ]]; then
    SECTIONS=$(python3 -c "import json,sys; d=json.load(open('/tmp/parse_response.json')); print(len(d.get('sections',[])))" 2>/dev/null || echo "?")
    echo "OK (${SECTIONS} sezioni)"
    SUCCESS=$((SUCCESS + 1))
  else
    echo "ERRORE (HTTP $HTTP_CODE)"
    cat /tmp/parse_response.json 2>/dev/null
    FAIL=$((FAIL + 1))
  fi
done

echo ""
echo "Completato: $SUCCESS OK, $FAIL errori."
