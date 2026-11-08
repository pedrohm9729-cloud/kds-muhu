# KDS Muhu

Pantalla de cocina (KDS) para Android. Se hace pasar por una impresora térmica de red: el POS (Wally) le "imprime" las comandas y la app las muestra en pantalla con cronómetro, semáforo, turnos y tiempos.

## Instalar en la tablet

1. Abre **Releases** en este repositorio y descarga `KDS-Muhu.apk` desde la tablet.
2. Ábrelo y permite "Instalar apps desconocidas" si Android lo pide.
3. Abre **KDS Muhu**. Acepta notificaciones y "sin restricción de batería".
4. En Wally agrega una impresora de red con la IP que muestra el KDS arriba (`Wally → …`), modelo **BIENEX E802**, ancho **48**, etiquetas de comandas.

## Pantallas

| Ruta | Uso |
|---|---|
| `http://IP:8080/cocina` | Cocina (la app abre esta) |
| `http://IP:8080/caja` | Caja / pase: ve el avance de cada estación y marca Entregado |
| `http://IP:8080/barra` | Barra (si activas "Separar barra y cocina") |
| `http://IP:8080/cliente` | Pantalla para clientes: Preparando / ¡Listo! |
| `http://IP:8080/tiempos` | Tiempos, ranking por encargado y ajustes del semáforo |
| `http://IP:8080/historial.csv` | Historial para Excel |

## Funcionamiento

- Puerto **9100**: impresora virtual ESC/POS (responde estado `DLE EOT` como impresora lista).
- Puerto **9101**: impresora virtual de barra (opcional): todo lo que llega ahí va a la barra.
- Puerto **8080**: servidor de pantallas con eventos en vivo.
- Arranca sola al prender la tablet. Puede elegirse como pantalla de inicio (modo kiosko).
- Si un ticket no se entiende, se muestra tal cual: nunca se pierde una comanda.

## Compilación

Cada cambio en `main` compila el APK automáticamente (GitHub Actions) y lo publica en **Releases**.
