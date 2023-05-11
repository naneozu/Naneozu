import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:get/get.dart';
import 'package:dio/dio.dart';
import 'package:nnz/src/controller/bottom_nav_controller.dart';

class MyPageService extends GetConnect {
  final token =
      'eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiIxMiIsImlzcyI6Im5ueiIsImlhdCI6MTY4Mzc3Nzg5OSwiYXV0aFByb3ZpZGVyIjoiTk5aIiwicm9sZSI6IlVTRVIiLCJpZCI6MTIsImVtYWlsIjoiamlqaUBnbWFpbC5jb20iLCJleHAiOjE2ODUwNzM4OTl9.LXCw6P2PfdC2MZNAVyPGLJrOixpVPhC6lczoeBuj4KqLu5l9ZMf6hmW6roO8WgduAFo8WhyQ_wI4aCigRWac5Q';
  final dio = Dio();

  @override
  void onInit() async {
    await dotenv.load();
    // dio.options.baseUrl = dotenv.env['BASE_URL'];
    dio.options.headers['Authorization'] = 'Bearer $token';
    // dio.options.connectTimeout = timeout;
    super.onInit();
  }

  Future<dynamic> getMyPageInfo() async {
    try {
      final response = await dio.get(
        'https://k8b207.p.ssafy.io/api/user-service/users',
        options: Options(
          headers: {
            "authorization": "Bearer $token",
          },
        ),
      );
      return response;
    } catch (e) {
      print(token);
      print('!!!!!Error occurred: $e');
      throw e;
    }
  }
}
