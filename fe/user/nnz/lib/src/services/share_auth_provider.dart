import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:get/get.dart';
import 'package:logger/logger.dart';
import 'package:nnz/src/config/token.dart';
import 'package:nnz/src/pages/share/sharing_complete.dart';

class ShareAuthProvider extends GetConnect {
  final logger = Logger();
  // final int nanumIds;

  // ShareAuthProvider(this.nanumIds);
  @override
  void onInit() async {
    await dotenv.load();

    //Set baseUrl from .env file
    httpClient.baseUrl = dotenv.env['BASE_URL'];
    httpClient.defaultContentType = '';
    httpClient.timeout = const Duration(microseconds: 5000);
    super.onInit();
  }

  Future<Response> postShareAuth(
      {required var authImage, required int nanumIds}) async {
    String? token;
    token = await Token.getAccessToken();
    final formData = FormData({});
    print("이게 이미지임");
    print(authImage);
    formData.files.add(MapEntry(
        'image', MultipartFile(authImage.path, filename: authImage.name)));
    final boundary = formData.boundary;
    final res = await post(
        "https://k8b207.p.ssafy.io/api/nanum-service/nanums/$nanumIds",
        formData,
        headers: {
          'Content-Type': 'multipart/form-data; boundary=$boundary',
          'Authorization': 'Bearer $token',
        });
    if (res.statusCode == 204) {
      Get.to(() => SharingComplete(
            nanumIds: nanumIds,
          ));
    } else {
      print(token);
      Get.snackbar("실패", "알수없는 오류로 실패하였습니다");
    }
    return res;
  }

  Future<Response> sendingShareAuth({required int nanumIds}) async {
    String? token;
    token = await Token.getAccessToken();
    final res = await post(
        "https://k8b207.p.ssafy.io/api/nanum-service/nanums/$nanumIds", {},
        headers: {
          'Authorization': 'Bearer $token',
        });
    if (res.statusCode == 204) {
      Get.to(() => SharingComplete(
            nanumIds: nanumIds,
          ));
    } else {
      Get.snackbar("실패", "알수없는 오류로 실패하였습니다");
    }
    return res;
  }
}
