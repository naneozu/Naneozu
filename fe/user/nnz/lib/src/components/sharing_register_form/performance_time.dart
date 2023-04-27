import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:logger/logger.dart';
import 'package:nnz/src/controller/sharing_register_controller.dart';
import 'package:platform_date_picker/platform_date_picker.dart';

import '../../config/config.dart';
import '../icon_data.dart';

class PerformanceTime extends StatefulWidget {
  const PerformanceTime({super.key});

  @override
  State<PerformanceTime> createState() => _PerformanceTimeState();
}

class _PerformanceTimeState extends State<PerformanceTime> {
  DateTime date = DateTime.now();
  final controller = Get.put(SharingRegisterController());
  final logger = Logger();
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 16,
        vertical: 8,
      ),
      color: Colors.white,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(
              vertical: 8,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                iconData(
                  icon: ImagePath.calendar,
                  size: 80,
                ),
                const SizedBox(
                  width: 12,
                ),
                const Padding(
                  padding: EdgeInsets.only(
                    bottom: 4.0,
                  ),
                  child: Text(
                    "공연 시간",
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Container(
            margin: const EdgeInsets.symmetric(
              vertical: 12,
            ),
            decoration: BoxDecoration(
              border: Border.all(
                color: Config.blackColor,
              ),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Container(
                    margin: const EdgeInsets.symmetric(
                      horizontal: 16,
                    ),
                    child: Text(
                      controller.performDate.value,
                      style: const TextStyle(
                        fontSize: 16,
                      ),
                    )),
                MaterialButton(
                  child: const Icon(
                    Icons.calendar_today,
                  ),
                  onPressed: () async {
                    DateTime? temp = await PlatformDatePicker.showDate(
                      context: context,
                      firstDate: DateTime(DateTime.now().year),
                      initialDate: DateTime.now(),
                      lastDate: DateTime(DateTime.now().year + 5),
                    );
                    if (temp != null) {
                      setState(() {
                        date = temp;
                        final dateFormat = date.toString().substring(0, 10);
                        controller.performDate(dateFormat);
                      });
                    }
                  },
                ),
              ],
            ),
          )
        ],
      ),
    );
  }
}