package com.example.apigoogle;

import com.google.gson.*;
import com.google.maps.routing.v2.*;
import com.google.type.LatLng;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.web.bind.annotation.*;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5174") // ← LÍNEA AGREGADA PARA CORS
public class HotelFinderController {

    private static class InterceptorConApiKey implements ClientInterceptor {
        private final String apiKey;
        private static final Metadata.Key<String> API_KEY_HEADER =
                Metadata.Key.of("x-goog-api-key", Metadata.ASCII_STRING_MARSHALLER);
        private static final Metadata.Key<String> FIELD_MASK_HEADER =
                Metadata.Key.of("x-goog-fieldmask", Metadata.ASCII_STRING_MARSHALLER);

        public InterceptorConApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(API_KEY_HEADER, apiKey);
                    headers.put(FIELD_MASK_HEADER, "routes.duration");
                    super.start(responseListener, headers);
                }
            };
        }
    }

    // ← ENDPOINT DE PRUEBA AGREGADO
    @GetMapping("/test")
    public String test() {
        return "✅ API funcionando correctamente en puerto 8082";
    }

    @GetMapping("/ruta")
    public String obtenerRuta(
            @RequestParam(name = "origen") String origen,
            @RequestParam(name = "destino") String destino) {
        try {
            System.out.println("🔍 Recibiendo petición - Origen: " + origen + ", Destino: " + destino);

            String apiKey = System.getenv("GOOGLE_MAPS_API_KEY");
            if (apiKey == null || apiKey.isEmpty()) {
                System.out.println("❌ API Key no encontrada");
                return "❌ API Key no configurada (GOOGLE_MAPS_API_KEY)";
            }

            System.out.println("✅ API Key encontrada, procesando geocodificación...");

            LatLng coordsOrigen = geocodeDireccion(origen, apiKey);
            LatLng coordsDestino = geocodeDireccion(destino, apiKey);

            System.out.println("✅ Coordenadas obtenidas, calculando ruta...");

            Channel canal = NettyChannelBuilder.forAddress("routes.googleapis.com", 443)
                    .useTransportSecurity()
                    .build();

            canal = ClientInterceptors.intercept(canal, new InterceptorConApiKey(apiKey));
            RoutesGrpc.RoutesBlockingStub stub = RoutesGrpc.newBlockingStub(canal);

            ComputeRoutesRequest solicitud = ComputeRoutesRequest.newBuilder()
                    .setOrigin(crearWaypoint(coordsOrigen.getLatitude(), coordsOrigen.getLongitude()))
                    .setDestination(crearWaypoint(coordsDestino.getLatitude(), coordsDestino.getLongitude()))
                    .setTravelMode(RouteTravelMode.DRIVE)
                    .setRoutingPreference(RoutingPreference.TRAFFIC_AWARE)
                    .setComputeAlternativeRoutes(false)
                    .build();

            ComputeRoutesResponse respuesta = stub.withDeadlineAfter(5000, TimeUnit.MILLISECONDS).computeRoutes(solicitud);

            if (respuesta.getRoutesCount() > 0) {
                Route ruta = respuesta.getRoutes(0);
                long minutos = ruta.getDuration().getSeconds() / 60;

                String resultado = String.format("🕒 Duración estimada del viaje: %d horas y %d minutos\n🌍 Ver ruta en Google Maps:\nhttps://www.google.com/maps/dir/%s/%s",
                        minutos / 60, minutos % 60,
                        URLEncoder.encode(origen, "UTF-8"),
                        URLEncoder.encode(destino, "UTF-8"));

                System.out.println("✅ Ruta calculada exitosamente");
                return resultado;
            } else {
                System.out.println("❌ No se encontró ruta");
                return "❌ No se encontró una ruta.";
            }

        } catch (Exception e) {
            System.out.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            return "❌ Error: " + e.getMessage();
        }
    }

    private LatLng geocodeDireccion(String direccion, String apiKey) throws Exception {
        String urlDireccion = URLEncoder.encode(direccion, "UTF-8");
        String urlStr = "https://maps.googleapis.com/maps/api/geocode/json?address=" + urlDireccion + "&key=" + apiKey;

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.connect();

        if (conn.getResponseCode() != 200) {
            throw new Exception("Error en geocodificación: " + conn.getResponseCode());
        }

        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        JsonObject jsonResponse = JsonParser.parseReader(reader).getAsJsonObject();
        reader.close();
        conn.disconnect();

        JsonArray results = jsonResponse.getAsJsonArray("results");
        if (results.size() == 0) {
            throw new Exception("No se encontraron coordenadas para: " + direccion);
        }

        JsonObject location = results.get(0)
                .getAsJsonObject().getAsJsonObject("geometry").getAsJsonObject("location");

        double lat = location.get("lat").getAsDouble();
        double lng = location.get("lng").getAsDouble();

        return LatLng.newBuilder().setLatitude(lat).setLongitude(lng).build();
    }

    private Waypoint crearWaypoint(double lat, double lng) {
        return Waypoint.newBuilder()
                .setLocation(Location.newBuilder().setLatLng(
                        LatLng.newBuilder().setLatitude(lat).setLongitude(lng)))
                .build();
    }
}