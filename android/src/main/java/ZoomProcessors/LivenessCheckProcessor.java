// Demonstrates performing a Liveness Check.

package ZoomProcessors;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import com.facetec.sdk.*;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;

public class LivenessCheckProcessor extends Processor implements FaceTecFaceScanProcessor {
    FaceTecFaceScanResultCallback faceTecFaceScanResultCallback;
    FaceTecSessionResult latestZoomSessionResult;
    private boolean _isSuccess = false;
    private final String licenseKey;

    public LivenessCheckProcessor(Context context, String licenseKey) {
        // Launch the ZoOm Session.
        this.licenseKey = licenseKey;
        FaceTecSessionActivity.createAndLaunchSession(context, this);
    }

    public boolean isSuccess() {
        return _isSuccess;
    }

    @Override
    public void processSessionWhileFaceTecSDKWaits(FaceTecSessionResult faceTecSessionResult, final FaceTecFaceScanResultCallback faceTecFaceScanResultCallback) {
        this.latestZoomSessionResult = faceTecSessionResult;
        this.faceTecFaceScanResultCallback = faceTecFaceScanResultCallback;

        // Cancel last request in flight.  This handles case where processing is is taking place but cancellation or Context Switch occurs.
        // Our handling here ends the latest in flight request and simply re-does the normal logic, which will cancel out.
        NetworkingHelpers.cancelPendingRequests();

        //
        // DEVELOPER NOTE:  These properties are for demonstration purposes only so the Sample App can get information about what is happening in the processor.
        // In the code in your own App, you can pass around signals, flags, intermediates, and results however you would like.
        //

        //
        // Part 3:  Handles early exit scenarios where there is no FaceScan to handle -- i.e. User Cancellation, Timeouts, etc.
        //
        if(faceTecSessionResult.getStatus() != FaceTecSessionStatus.SESSION_COMPLETED_SUCCESSFULLY) {
            NetworkingHelpers.cancelPendingRequests();
            faceTecFaceScanResultCallback.cancel();
            return;
        }

        // IMPORTANT:  FaceTecSDK.FaceTecSessionStatus.SessionCompletedSuccessfully DOES NOT mean the Liveness Check was Successful.
        // It simply means the User completed the Session and a 3D FaceScan was created.  You still need to perform the Liveness Check on your Servers.

        //
        // Part 4:  Get essential data off the FaceTecSessionResult
        //
        JSONObject parameters = new JSONObject();
        try {
            parameters.put("faceScan", faceTecSessionResult.getFaceScanBase64());
            parameters.put("auditTrailImage", faceTecSessionResult.getAuditTrailCompressedBase64()[0]);
            parameters.put("lowQualityAuditTrailImage", faceTecSessionResult.getLowQualityAuditTrailCompressedBase64()[0]);
        }
        catch(JSONException e) {
            e.printStackTrace();
            Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to create JSON payload for upload.");
        }

        //
        // Part 5:  Make the Networking Call to Your Servers.  Below is just example code, you are free to customize based on how your own API works.
        //
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(ZoomGlobalState.ZoomServerBaseURL + "/liveness-3d")
                .header("Content-Type", "application/json")
                .header("X-Device-Key", licenseKey)
                .header("User-Agent", FaceTecSDK.createFaceTecAPIUserAgentString(faceTecSessionResult.getSessionId()))

                //
                // Part 7:  Demonstrates updating the Progress Bar based on the progress event.
                //
                .post(new ProgressRequestBody(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), parameters.toString()),
                        new ProgressRequestBody.Listener() {
                            @Override
                            public void onUploadProgressChanged(long bytesWritten, long totalBytes) {
                                final float uploadProgressPercent = ((float)bytesWritten) / ((float)totalBytes);
                                faceTecFaceScanResultCallback.uploadProgress(uploadProgressPercent);
                            }
                        }))
                .build();



        NetworkingHelpers.getApiClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, okhttp3.Response response) throws IOException {
                String responseString = response.body().string();
                response.body().close();
                try {
                    JSONObject responseJSON = new JSONObject(responseString);
                    boolean wasProcessed = responseJSON.getBoolean("wasProcessed");
                    String scanResultBlob = responseJSON.getString("scanResultBlob");

                    // In v9.2.0+, we key off a new property called wasProcessed to determine if we successfully processed the Session result on the Server.
                    // Device SDK UI flow is now driven by the proceedToNextStep function, which should receive the scanResultBlob from the Server SDK response.
                    if(wasProcessed) {

                        // Demonstrates dynamically setting the Success Screen Message.
                        FaceTecCustomization.overrideResultScreenSuccessMessage = "Liveness\nConfirmed";

                        // In v9.2.0+, simply pass in scanResultBlob to the proceedToNextStep function to advance the User flow.
                        // scanResultBlob is a proprietary, encrypted blob that controls the logic for what happens next for the User.
                        _isSuccess = faceTecFaceScanResultCallback.proceedToNextStep(scanResultBlob);
                    }
                    else {
                        // CASE:  UNEXPECTED response from API.  Our Sample Code keys off a wasProcessed boolean on the root of the JSON object --> You define your own API contracts with yourself and may choose to do something different here based on the error.
                        faceTecFaceScanResultCallback.cancel();
                    }
                }  catch(JSONException e) {
                    // CASE:  Parsing the response into JSON failed --> You define your own API contracts with yourself and may choose to do something different here based on the error.  Solid server-side code should ensure you don't get to this case.
                    e.printStackTrace();
                    Log.d("FaceTecSDKSampleApp", "Exception raised while attempting to parse JSON result.");
                    faceTecFaceScanResultCallback.cancel();
                }

            }

            @Override
            public void onFailure(Call call,IOException e) {
                // CASE:  Network Request itself is erroring --> You define your own API contracts with yourself and may choose to do something different here based on the error.
                Log.d("FaceTecSDKSampleApp", "Exception raised while attempting HTTPS call.");
                faceTecFaceScanResultCallback.cancel();
            }
        });
    }
}
